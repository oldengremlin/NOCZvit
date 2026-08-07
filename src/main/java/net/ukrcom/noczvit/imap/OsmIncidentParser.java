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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Домен: парсить листи-алерти OSM/SDH в об'єкти {@link Incident}. Без I/O —
 * отримує {@link RawMessage} і повертає доменний об'єкт.
 */
@Slf4j
public class OsmIncidentParser {

    private static final DateTimeFormatter TRAP_DATE_INPUT_FORMATTER
            = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter TRAP_DATE_OUTPUT_FORMATTER
            = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private static final Pattern PATTERN_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    // Наскільки Trap value має випереджати свій алерт у часі, щоб приписка «який відбувся …»
    // мала сенс. Менший розрив — це розбіжність годинників чи затримка обробки між хостом OSM
    // і поштовим сервером (спостерігалась ~2 хвилини), і приписка лише повторила б колонку
    // «Початок». Лише справді запізнілий звіт повідомляє те, чого ця колонка сказати не може.
    private static final long TRAP_NOTE_MIN_LAG_SEC = 5 * 60;

    private final Dictionary dictionary;

    public OsmIncidentParser(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Повертає Incident, якщо повідомлення є валідним алертом OSM, інакше empty.
     *
     * @param msg
     * @return
     */
    public Optional<Incident> parse(RawMessage msg) {
        String subject = msg.subject();
        log.debug("Processing SDH message: subject={}, ts={}", subject, msg.unixDate());

        String[] parts = subject.split("\\s+");
        String geo = parts.length > 3 ? parts[3] : "";
        String type = parts.length > 5 ? parts[5] : "";

        String from = geo;
        String to = "";
        if ("STM".equals(type)) {
            String[] geoParts = geo.split("__");
            from = geoParts[0];
            to = geoParts.length > 1 ? geoParts[1] : "";
        }

        Dictionary.Resolution fromRes = dictionary.resolveSDH(from);
        from = fromRes.value();
        boolean needsReviewFrom = fromRes.needsReview();

        // Відсутній "to" (теми не STM) резолвиться сам у себе і виглядав би нерезолвленим —
        // порожній код це не відсутній запис у словнику, тож він ніколи не йде на review.
        String originalTo = to;
        Dictionary.Resolution toRes = dictionary.resolveSDH(to);
        to = toRes.value();
        boolean needsReviewTo = !originalTo.isEmpty() && toRes.needsReview();

        String geoMsg = "STM".equals(type)
                        ? (to.isEmpty() ? "на " + from : "з " + from + " на " + to)
                        : from;

        Status status = IncidentDescriptions.resolveStatus(subject);

        String eventDesc;
        if ("Power".equals(type)) {
            eventDesc = subject.contains("Air Condition")
                        ? "зникнення живлення на " + geoMsg + " до кондиціонерів"
                        : "зникнення живлення на виносі " + geoMsg;
        } else {
            eventDesc = "втрата зв'язності " + geoMsg;
        }
        String description = IncidentDescriptions.describe(
                IncidentDescriptions.SOURCE_OSM, status, eventDesc, "інцидент, ");

        // Видобуваємо точний час події з Trap value в тілі листа
        long eventTs = msg.unixDate();
        String eventDateStr = msg.dateStr();
        String[] lines = msg.body().replace("\r", "").split("\n");
        for (String line : lines) {
            if (line.startsWith("Trap value:")) {
                log.debug("Trap value line: {}", line);
                Matcher matcher = PATTERN_DATE.matcher(line);
                if (matcher.find()) {
                    try {
                        LocalDateTime ldt = LocalDateTime.parse(matcher.group(), TRAP_DATE_INPUT_FORMATTER);
                        eventTs = DateUtils.toInstant(ldt, msg.unixDate()).getEpochSecond();
                        eventDateStr = ldt.atZone(ZoneId.systemDefault()).format(TRAP_DATE_OUTPUT_FORMATTER);
                        log.debug("Found Trap value date: {}, updated ts={}", matcher.group(), eventTs);
                    } catch (DateTimeParseException e) {
                        log.warn("Failed to parse Trap value date: {} — {}", matcher.group(), e.getMessage());
                    }
                }
                break;
            }
        }

        // Подія не може статися пізніше за алерт, що про неї повідомляє, проте OSM регулярно
        // надсилає Trap value на кілька хвилин раніше за власний лист — це розбіжність
        // годинників хоста OSM і поштового сервера, а не реальний час події. Обрізаємо такі
        // значення до часу алерту.
        if (eventTs > msg.unixDate()) {
            log.debug("Trap value {} is after the alert ({}), clamping to the alert time",
                    eventTs, msg.unixDate());
            eventTs = msg.unixDate();
            eventDateStr = msg.dateStr();
        }

        // Лише Trap value, що випереджає алерт більше за поріг розбіжності годинників,
        // повідомляє те, чого не каже колонка «Початок» — подія настільки довго лишалась
        // незвітованою. Все ближче (включно з усім щойно обрізаним вище) нічого не додає,
        // тож приписку опускаємо. eventTs у будь-якому разі лишається як заявлено;
        // приховується лише приписка.
        if (msg.unixDate() - eventTs >= TRAP_NOTE_MIN_LAG_SEC) {
            description += ", який відбувся " + DateUtils.convertMonthNumToMnemo(eventDateStr);
        }

        List<String> reviewNames = new ArrayList<>();
        if (needsReviewFrom) {
            reviewNames.add(from);
        }
        if (needsReviewTo) {
            reviewNames.add(to);
        }

        String messageDateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());
        String eventDateLoc = DateUtils.convertMonthNumToMnemo(eventDateStr);

        log.debug("SDH stored: from={}, to={}, ts={}", from, to, msg.unixDate());
        return Optional.of(new Incident(
                from, "",
                msg.unixDate(), eventTs,
                messageDateLoc, eventDateLoc,
                Source.OSM, status,
                description, List.copyOf(reviewNames),
                msg.inReplyTo()
        ));
    }
}
