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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;

/**
 * Domain: parses Zabbix ospfNbrStateChange alert emails into {@link Incident} objects.
 * Subject format: "[±] Problem/Resolved: <host>: <router> <channel> ospfNbrStateChange"
 */
@Slf4j
public class OspfIncidentParser {

    private final Dictionary dictionary;

    public OspfIncidentParser(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Returns an Incident if the message is a valid OSPF neighbor state change alert.
     */
    public Optional<Incident> parse(RawMessage msg) {
        String subject = msg.subject();
        String[] parts = subject.split("\\s+");
        // parts[2] = "host:", parts[3] = router, parts[4] = channel
        String router  = parts.length > 3 ? parts[3] : "";
        String channel = parts.length > 4 ? parts[4] : "";

        String originalRouter = router;
        router = dictionary.lookupPD(router);
        boolean needsReviewRouter = originalRouter.equals(router);

        String originalChannel = channel;
        channel = dictionary.lookupPD(channel);
        boolean needsReviewChannel = originalChannel.equals(channel);

        Status status = resolveStatus(subject);
        String statePart = switch (status) {
            case START -> "Zabbix зареєстровано початок інциденту, ";
            case END   -> "Zabbix зареєстровано кінець інциденту, ";
            case NONE  -> "Zabbix зареєстровано ";
        };
        String description = (statePart + "падіння каналу на " + router + " по каналу " + channel)
                .replaceAll("\\s+", " ");

        List<String> reviewNames = new ArrayList<>();
        if (needsReviewRouter)  reviewNames.add(originalRouter);
        if (needsReviewChannel) reviewNames.add(originalChannel);

        String dateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());

        log.debug("OSPF parsed: router={}, channel={}, ts={}", router, channel, msg.unixDate());
        return Optional.of(new Incident(
                router, "",
                msg.unixDate(), msg.unixDate(),
                dateLoc, dateLoc,
                Source.PD, status,
                description, List.copyOf(reviewNames)
        ));
    }

    private Status resolveStatus(String subject) {
        if (subject.contains(" Resolved:")) return Status.END;
        if (subject.contains(" Problem:"))  return Status.START;
        return Status.NONE;
    }
}
