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
import java.util.List;
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

    private final Config config;
    private final ImapReader reader;
    private final PdIncidentParser pdParser;
    private final OsmIncidentParser osmParser;
    private final OspfIncidentParser ospfParser;
    private final AdlinkIncidentParser adlinkParser;

    public Client(Config config) throws IOException {
        this.config = config;
        Dictionary dictionary = new Dictionary(config);
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
            if (isPdMessage(msg.subject())) {
                if (msg.unixDate() >= fromEpoch && msg.unixDate() <= toEpoch) {
                    pdParser.parse(msg).ifPresent(incidents::add);
                } else {
                    log.debug("Skipping PD message (time filter): unixDate={}", msg.unixDate());
                }
            } else if (isOspfMessage(msg.subject())) {
                if (msg.unixDate() >= fromEpoch && msg.unixDate() <= toEpoch) {
                    ospfParser.parse(msg).ifPresent(incidents::add);
                } else {
                    log.debug("Skipping OSPF message (time filter): unixDate={}", msg.unixDate());
                }
            } else if (isAdlinkMessage(msg.subject())) {
                if (msg.unixDate() >= fromEpoch && msg.unixDate() <= toEpoch) {
                    adlinkParser.parse(msg).ifPresent(incidents::add);
                } else {
                    log.debug("Skipping adlink message (time filter): unixDate={}", msg.unixDate());
                }
            } else if (isOsmMessage(msg.subject())) {
                osmParser.parse(msg)
                        .filter(i -> i.messageTs() >= fromEpoch && i.messageTs() <= toEpoch)
                        .ifPresent(incidents::add);
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

        List<RawMessage> result = new ArrayList<>();
        List<RawMessage> seenAdlinks = new ArrayList<>();
        for (RawMessage msg : sorted) {
            if (!isAdlinkMessage(msg.subject())) {
                result.add(msg);
                continue;
            }
            boolean isDuplicate = seenAdlinks.stream().anyMatch(seen
                    -> seen.subject().equals(msg.subject())
                    && msg.unixDate() - seen.unixDate() <= 60);
            if (!isDuplicate) {
                result.add(msg);
                seenAdlinks.add(msg);
            } else {
                log.debug("Deduplicating adlink message: subject={}, ts={}", msg.subject(), msg.unixDate());
            }
        }
        return result;
    }

    private boolean isPdMessage(String subject) {
        return subject.matches(".*(?:Unavailable by ICMP ping|has been restarted).*");
    }

    private boolean isOspfMessage(String subject) {
        return subject.contains("ospfNbrStateChange");
    }

    private boolean isAdlinkMessage(String subject) {
        return subject.contains("adlink") && subject.contains("- Fault");
    }

    private boolean isOsmMessage(String subject) {
        String stmPattern = "2-9";
        if (config.isDebug()) {
            stmPattern = "1-9";
        }
        return subject.matches(".*(?:[Pp][Oo][Ww][Ee][Rr]|STM [Ss][Tt][Mm].?[" + stmPattern + "][0-9]*).*");
    }
}
