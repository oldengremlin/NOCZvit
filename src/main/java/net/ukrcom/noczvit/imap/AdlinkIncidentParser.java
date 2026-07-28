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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;

/**
 * Domain: parses Zabbix dry-contact (adlink) alert emails into {@link Incident} objects.
 * Subject format: "[±] Problem/Resolved: <device>: card N, port N, line N - Fault"
 *
 * Dictionary (dictionary_pd.txt) controls semantics:
 *   ^adlink-hoh15-1:0:0:0=зникнення живлення на кондиціонери (лінія 0)
 *   ^adlink-hoh15-1=Г.Джонса 15
 * The line-key lookup returns the event description; device lookup returns the location.
 * Unknown keys fall back to a generic description and are added to reviewNames.
 */
@Slf4j
public class AdlinkIncidentParser {

    private static final Pattern ADLINK_PATTERN =
            Pattern.compile("(adlink[\\w-]+):\\s*card\\s+(\\d+),\\s*port\\s+(\\d+),\\s*line\\s+(\\d+)");

    private final Dictionary dictionary;

    public AdlinkIncidentParser(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Returns an Incident if the subject matches the adlink dry-contact pattern.
     */
    public Optional<Incident> parse(RawMessage msg) {
        String subject = msg.subject();
        Matcher matcher = ADLINK_PATTERN.matcher(subject);
        if (!matcher.find()) {
            log.warn("Adlink subject did not match expected pattern: {}", subject);
            return Optional.empty();
        }

        String device  = matcher.group(1);
        String card    = matcher.group(2);
        String port    = matcher.group(3);
        String line    = matcher.group(4);
        String lineKey = device + ":" + card + ":" + port + ":" + line;

        String location = dictionary.lookupPD(device);
        boolean needsReviewLocation = device.equals(location);

        String eventDesc = dictionary.lookupPD(lineKey);
        boolean needsReviewEvent = lineKey.equals(eventDesc);
        if (needsReviewEvent) {
            eventDesc = "спрацювання сухого контакту, лінія " + line;
        }

        Status status = resolveStatus(subject);
        String statePart = switch (status) {
            case START -> "Zabbix зареєстровано початок інциденту, ";
            case END   -> "Zabbix зареєстровано кінець інциденту, ";
            case NONE  -> "Zabbix зареєстровано ";
        };
        String description = (statePart + eventDesc).replaceAll("\\s+", " ");

        List<String> reviewNames = new ArrayList<>();
        if (needsReviewLocation) reviewNames.add(device);
        if (needsReviewEvent)    reviewNames.add(lineKey);

        String dateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());
        log.debug("Adlink parsed: device={}, lineKey={}, ts={}", device, lineKey, msg.unixDate());
        return Optional.of(new Incident(
                location, "",
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
