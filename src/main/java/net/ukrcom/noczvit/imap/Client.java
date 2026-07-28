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
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;

/**
 * Orchestrates IMAP reading and incident parsing.
 * Delegates I/O to {@link ImapReader}, business logic to {@link PdIncidentParser}
 * and {@link OsmIncidentParser}.
 */
@Slf4j
public class Client {

    private final Config config;
    private final ImapReader reader;
    private final PdIncidentParser pdParser;
    private final OsmIncidentParser osmParser;

    public Client(Config config) throws IOException {
        this.config = config;
        Dictionary dictionary = new Dictionary(config);
        this.reader = new ImapReader(config);
        this.pdParser = new PdIncidentParser(dictionary);
        this.osmParser = new OsmIncidentParser(dictionary);
    }

    /**
     * Reads messages from IMAP and parses them into incidents covering both duty periods.
     *
     * @return all incidents found within [prevDutyBegin, currDutyEnd]
     */
    public List<Incident> prepareImapFolder(boolean isInteractive,
                                            LocalDateTime prevDutyBegin,
                                            LocalDateTime prevDutyEnd,
                                            LocalDateTime currDutyBegin,
                                            LocalDateTime currDutyEnd) {
        long fromEpoch = prevDutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long toEpoch   = currDutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        log.debug("Filter period: {} … {}", prevDutyBegin, currDutyEnd);

        List<RawMessage> rawMessages;
        try {
            rawMessages = reader.readMessages(config.isDebug(), fromEpoch, toEpoch);
        } catch (MessagingException e) {
            log.error("IMAP error: {}", e.getMessage());
            throw new RuntimeException("IMAP error: " + e.getMessage(), e);
        }

        List<Incident> incidents = new ArrayList<>();
        for (RawMessage msg : rawMessages) {
            if (isPdMessage(msg.subject())) {
                if (msg.unixDate() >= fromEpoch && msg.unixDate() <= toEpoch) {
                    pdParser.parse(msg).ifPresent(incidents::add);
                } else {
                    log.debug("Skipping PD message (time filter): unixDate={}", msg.unixDate());
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

    private boolean isPdMessage(String subject) {
        return subject.matches(".*(?:Unavailable by ICMP ping|has been restarted).*");
    }

    private boolean isOsmMessage(String subject) {
        String stmPattern = "2-9";
        if (config.isDebug()) {
            stmPattern = "1-9";
        }
        return subject.matches(".*(?:[Pp][Oo][Ww][Ee][Rr]|STM [Ss][Tt][Mm].?[" + stmPattern + "][0-9]*).*");
    }
}
