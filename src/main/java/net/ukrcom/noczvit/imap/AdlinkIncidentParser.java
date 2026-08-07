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
 * Домен: парсить листи-алерти Zabbix про сухі контакти (adlink) в об'єкти
 * {@link Incident}. Формат теми: "[±] Problem/Resolved: <device>: card N, port N,
 * line N - Fault"
 *
 * Семантику визначає словник (dictionary_pd.txt):
 * ^adlink-hoh15-1:0:0:0=зникнення живлення на кондиціонери (лінія 0)
 * ^adlink-hoh15-1=Г.Джонса 15 Пошук за ключем лінії повертає опис
 * події; пошук за пристроєм повертає локацію. Невідомі ключі отримують
 * загальний опис і додаються до reviewNames.
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
     * Повертає Incident, якщо тема відповідає патерну сухого контакту
     * adlink.
     *
     * @param msg сире email-повідомлення
     * @return розпізнаний інцидент, або {@link Optional#empty()}, якщо тема не відповідає формату
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
        // ВІДОМА ОСОБЛИВІСТЬ (свідомо не виправлено — додавання нових ліній adlink контрольований
        // процес): «голий» запис пристрою в словнику (^adlink-hoh15-1=Локація, без card:port:line)
        // — це регекс без прив'язки до кінця рядка, тож він збігається як префікс з БУДЬ-яким
        // composite-ключем цього ж пристрою. Немаплена лінія (наприклад line 2, коли задані лише
        // 0 і 1) підхопить локацію пристрою замість "потребує коригування назви" — резолвиться
        // мовчки, без review. Ризикує лише тоді, коли з'явиться нова лінія без окремого запису.
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
