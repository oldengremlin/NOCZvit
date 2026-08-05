/*
 * Copyright 2025 Ukrcom
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package net.ukrcom.noczvit.imap;

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;

/**
 * Orchestrates IMAP reading and incident parsing. Delegates I/O to
 * {@link ImapReader}, business logic to {@link PdIncidentParser} and
 * {@link OsmIncidentParser}.
 */
@Slf4j
public class Client {

    // Zabbix emits each adlink alert twice within seconds; collapse repeats of the same
    // subject that arrive within this window.
    private static final long ADLINK_DEDUP_WINDOW_SEC = 60;

    private final Config config;
    private final ImapReader reader;
    private final PdIncidentParser pdParser;
    private final OsmIncidentParser osmParser;
    private final OspfIncidentParser ospfParser;
    private final AdlinkIncidentParser adlinkParser;

    /**
     * Creates the client using an already-loaded dictionary.
     *
     * <p>The dictionary is passed in rather than constructed here so that the whole process
     * shares one instance: building a second one re-read both files, recompiled every pattern
     * and gave the parsers a lookup cache separate from the one {@code ZabbixIncidentConverter}
     * uses.
     */
    public Client(Config config, Dictionary dictionary) {
        this.config = config;
        this.reader = new ImapReader(config);
        this.pdParser = new PdIncidentParser(dictionary);
        this.osmParser = new OsmIncidentParser(dictionary);
        this.ospfParser = new OspfIncidentParser(dictionary);
        this.adlinkParser = new AdlinkIncidentParser(dictionary);
    }

    /**
     * Reads messages from IMAP and parses them into incidents covering both
     * duty periods.
     *
     * @param isInteractive
     * @param prevDutyBegin
     * @param prevDutyEnd
     * @param currDutyBegin
     * @param currDutyEnd
     * @return all incidents found within [prevDutyBegin, currDutyEnd]
     */
    public List<Incident> prepareImapFolder(boolean isInteractive,
                                            LocalDateTime prevDutyBegin,
                                            LocalDateTime prevDutyEnd,
                                            LocalDateTime currDutyBegin,
                                            LocalDateTime currDutyEnd) {
        long fromEpoch = prevDutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long toEpoch = currDutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        log.debug("Filter period: {} … {}", prevDutyBegin, currDutyEnd);

        List<RawMessage> rawMessages;
        try {
            rawMessages = reader.readMessages(config.isDebug(), fromEpoch, toEpoch);
        } catch (MessagingException e) {
            log.error("IMAP error: {}", e.getMessage());
            throw new RuntimeException("IMAP error: " + e.getMessage(), e);
        }

        List<RawMessage> deduped = deduplicateAdlink(rawMessages);

        List<Incident> incidents = new ArrayList<>();
        for (RawMessage msg : deduped) {
            // The window guard is identical for every source, so it is applied once up front.
            // The OSM branch used to filter after parsing, on Incident.messageTs — which is
            // assigned msg.unixDate() anyway, so the outcome is unchanged and out-of-window
            // messages are no longer parsed just to be discarded.
            if (msg.unixDate() < fromEpoch || msg.unixDate() > toEpoch) {
                log.debug("Skipping message (time filter): unixDate={}, subject={}",
                        msg.unixDate(), msg.subject());
                continue;
            }
            if (isPdMessage(msg.subject())) {
                pdParser.parse(msg).ifPresent(incidents::add);
            } else if (isOspfMessage(msg.subject())) {
                ospfParser.parse(msg).ifPresent(incidents::add);
            } else if (isAdlinkMessage(msg.subject())) {
                adlinkParser.parse(msg).ifPresent(incidents::add);
            } else if (isOsmMessage(msg.subject())) {
                osmParser.parse(msg).ifPresent(incidents::add);
            }
        }

        log.info("IMAP processing done: {} incidents", incidents.size());
        return incidents;
    }

    /**
     * Removes duplicate adlink alerts: Zabbix sends each alert twice within
     * seconds. Keeps the first occurrence of each (subject, status) pair within
     * a 60-second window. Deduplication runs before duty-period filtering so
     * cross-boundary duplicates (e.g. 07:59:59 and 08:00:03) are correctly
     * collapsed into the earlier shift.
     */
    private List<RawMessage> deduplicateAdlink(List<RawMessage> messages) {
        List<RawMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparingLong(RawMessage::unixDate));

        // Last kept timestamp per subject — same map-based approach as TrapDeduplicator.
        // The previous version rescanned a growing list of every adlink seen so far, which is
        // quadratic; since the input is already sorted ascending, only the last kept one matters.
        Map<String, Long> lastKept = new HashMap<>();
        List<RawMessage> result = new ArrayList<>();
        for (RawMessage msg : sorted) {
            if (!isAdlinkMessage(msg.subject())) {
                result.add(msg);
                continue;
            }
            Long previous = lastKept.get(msg.subject());
            if (previous == null || msg.unixDate() - previous > ADLINK_DEDUP_WINDOW_SEC) {
                lastKept.put(msg.subject(), msg.unixDate());
                result.add(msg);
            } else {
                log.debug("Deduplicating adlink message: subject={}, ts={}", msg.subject(), msg.unixDate());
            }
        }
        return result;
    }

    /** Returns {@code true} for ICMP-ping or device-restart alert subjects handled by {@link PdIncidentParser}. */
    private boolean isPdMessage(String subject) {
        return subject.matches(".*(?:Unavailable by ICMP ping|has been restarted).*");
    }

    /** Returns {@code true} for OSPF neighbour state-change alert subjects handled by {@link OspfIncidentParser}. */
    private boolean isOspfMessage(String subject) {
        return subject.contains("ospfNbrStateChange");
    }

    /** Returns {@code true} for Zabbix dry-contact (adlink) alert subjects handled by {@link AdlinkIncidentParser}. */
    private boolean isAdlinkMessage(String subject) {
        return subject.contains("adlink") && subject.contains("- Fault");
    }

    /**
     * Returns {@code true} for SDH/OSM power-loss or STM circuit alert subjects handled by
     * {@link OsmIncidentParser}. In debug mode STM-1 alerts are also included.
     */
    private boolean isOsmMessage(String subject) {
        String stmPattern = "2-9";
        if (config.isDebug()) {
            stmPattern = "1-9";
        }
        return subject.matches(".*(?:[Pp][Oo][Ww][Ee][Rr]|STM [Ss][Tt][Mm].?[" + stmPattern + "][0-9]*).*");
    }
}
