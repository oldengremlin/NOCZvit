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
package net.ukrcom.noczvit.trap;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.imap.RawMessage;

/**
 * Розбирає сирі IMAP-листи з SNMP-трапами від приймача Emerson/Liebert у
 * об'єкти {@link TrapEvent}.
 *
 * <p>Очікувана тема листа: {@code Got trap from HOSTNAME - Trap Registrator <...> - DATE}
 *
 * <p>Очікуване тіло (один рядок, потім перенос рядка + тип трапа):
 * {@code At DD-MM-YYYY HH:mm:ss, from IP, after uptime U, registered trap:}
 * {@code \r\n\tTRAP_TYPE}
 *
 * <p>Клас пристрою визначається за префіксом хостнейму: {@code adc*} → ADC, {@code pdc*} → PDC.
 */
@Slf4j
public class EmersonTrapParser {

    private static final Pattern SUBJECT_RE = Pattern.compile(
            "^Got trap from ([\\w-]+)",
            Pattern.CASE_INSENSITIVE);

    // Спільний заголовок (група 1 = мітка часу, група 2 = IP) + власне тіло Emerson: довільний
    // текст після "registered trap:" — група 3.
    private static final Pattern BODY_RE = Pattern.compile(
            TrapMailFormat.HEADER_PREFIX
            + "\\s+after\\s+uptime\\s+[\\d:.]+,\\s*registered\\s+trap:\\s*\\r?\\n\\s+(.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private EmersonTrapParser() {
    }

    /**
     * Розбирає список сирих IMAP-листів у події трапів.
     * Листи, які не вдалось розібрати (неочікуваний формат теми/тіла, невідомий клас пристрою),
     * пропускаються з записом WARN у лог.
     *
     * @param messages сирі листи з IMAP-тек SNMP-трапів
     * @return список розібраних {@link TrapEvent}; ніколи не null
     */
    public static List<TrapEvent> parse(List<RawMessage> messages) {
        List<TrapEvent> result = new ArrayList<>();
        for (RawMessage msg : messages) {
            parse(msg).ifPresent(result::add);
        }
        return result;
    }

    /**
     * Розбирає один сирий лист у подію трапа, повертаючи {@link Optional#empty()},
     * якщо тема, тіло чи клас пристрою не розпізнані.
     */
    private static Optional<TrapEvent> parse(RawMessage msg) {
        Matcher subjectMatcher = SUBJECT_RE.matcher(msg.subject());
        if (!subjectMatcher.find()) {
            log.warn("EmersonTrapParser: unexpected subject format, skipping: «{}»", msg.subject());
            return Optional.empty();
        }
        String hostname = subjectMatcher.group(1).toLowerCase(Locale.ROOT);

        String deviceClass = classifyHostname(hostname);
        if (deviceClass == null) {
            log.warn("EmersonTrapParser: unknown device class for hostname «{}», skipping", hostname);
            return Optional.empty();
        }

        String body = msg.body();
        if (body == null || body.isBlank()) {
            log.warn("EmersonTrapParser: empty body for message from hostname «{}»", hostname);
            return Optional.empty();
        }

        Matcher bodyMatcher = BODY_RE.matcher(body);
        if (!bodyMatcher.find()) {
            log.warn("EmersonTrapParser: unexpected body format for hostname «{}», body starts: «{}»",
                    hostname, body.substring(0, Math.min(80, body.length())));
            return Optional.empty();
        }

        String ip = bodyMatcher.group(2).trim();
        String rawTrapType = bodyMatcher.group(3).trim();
        String trapType = normalizeTrapType(rawTrapType);

        Instant timestamp;
        try {
            timestamp = DateUtils.toInstant(
                    LocalDateTime.parse(bodyMatcher.group(1).trim(), TrapMailFormat.HEADER_TIMESTAMP), msg.unixDate());
        } catch (DateTimeParseException e) {
            // Повертаємось до мітки часу з заголовка листа Date:
            timestamp = Instant.ofEpochSecond(msg.unixDate());
            log.debug("EmersonTrapParser: body timestamp parse failed for hostname «{}», using email date", hostname);
        }

        return Optional.of(new TrapEvent(timestamp, ip, hostname, trapType, deviceClass));
    }

    /**
     * Нормалізує сирий рядок типу трапа: знімає обрамляючі подвійні лапки й згортає
     * послідовності {@code ": "} (двокрапка-пробіл) до {@code ":"}.
     */
    static String normalizeTrapType(String raw) {
        String s = raw.strip();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replaceAll(":\\s+", ":");
    }

    /**
     * Визначає клас пристрою за префіксом хостнейму: {@code adc*} → ADC, {@code pdc*} → PDC,
     * інакше {@code null}.
     */
    private static String classifyHostname(String hostname) {
        if (hostname.startsWith("adc")) {
            return TrapEvent.CLASS_ADC;
        }
        if (hostname.startsWith("pdc")) {
            return TrapEvent.CLASS_PDC;
        }
        return null;
    }
}
