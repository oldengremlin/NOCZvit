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
import net.ukrcom.noczvit.model.IncidentDescriptions;

/**
 * Domain: parses Zabbix dry-contact (adlink) alert emails into {@link Incident}
 * objects. Subject format: "[±] Problem/Resolved: <device>: card N, port N,
 * line N - Fault"
 *
 * Dictionary (dictionary_pd.txt) controls semantics:
 * ^adlink-hoh15-1:0:0:0=зникнення живлення на кондиціонери (лінія 0)
 * ^adlink-hoh15-1=Г.Джонса 15 The line-key lookup returns the event
 * description; device lookup returns the location. Unknown keys fall back to a
 * generic description and are added to reviewNames.
 */
@Slf4j
public class AdlinkIncidentParser {

    private static final Pattern ADLINK_PATTERN
            = Pattern.compile("(?i)(adlink[\\w-]+):\\s*(?:Trap\\s+)?" + Dictionary.CARD_PORT_LINE_REGEX);

    private final Dictionary dictionary;

    public AdlinkIncidentParser(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Returns an Incident if the subject matches the adlink dry-contact
     * pattern.
     *
     * @param msg
     * @return
     */
    public Optional<Incident> parse(RawMessage msg) {
        String subject = msg.subject();
        Matcher matcher = ADLINK_PATTERN.matcher(subject);
        if (!matcher.find()) {
            log.warn("Adlink subject did not match expected pattern: {}", subject);
            return Optional.empty();
        }

        String device = matcher.group(1);
        String line = matcher.group(4);
        String lineKey = Dictionary.lineKey(device, matcher.group(2), matcher.group(3), line);

        Dictionary.Resolution location = dictionary.resolvePD(device);
        Dictionary.Resolution event = dictionary.resolvePD(lineKey);

        String eventDesc = event.needsReview()
                           ? "спрацювання сухого контакту, лінія " + line
                           : event.value();

        Status status = IncidentDescriptions.resolveStatus(subject);
        String description = IncidentDescriptions.describe(
                IncidentDescriptions.SOURCE_ZABBIX, status, eventDesc);

        List<String> reviewNames = new ArrayList<>();
        if (location.needsReview()) {
            reviewNames.add(device);
        }
        if (event.needsReview()) {
            reviewNames.add(lineKey);
        }

        String dateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());
        log.debug("Adlink parsed: device={}, lineKey={}, ts={}", device, lineKey, msg.unixDate());
        return Optional.of(new Incident(
                location.value(), "",
                msg.unixDate(), msg.unixDate(),
                dateLoc, dateLoc,
                Source.PD, status,
                description, List.copyOf(reviewNames),
                msg.inReplyTo()
        ));
    }
}
