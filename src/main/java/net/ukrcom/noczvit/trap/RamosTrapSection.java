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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.imap.DateUtils;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Рендерить об'єкти {@link RamosTrapEvent} у секцію HTML-звіту та готовий для Claude
 * блок звичайного тексту.
 *
 * <p>Події групуються за залом (Room1–Room4, потім «Інші») і сортуються за timestamp у межах
 * кожної групи. Усі рядки використовують нейтральний почерговий фон, спільний для всіх таблиць
 * звіту. Текстовий вивід містить лише події з {@link RamosTrapEvent#CLAUDE_STATES} — див. там,
 * чому цей набір не просто "Critical" (падіння й зростання температури — не симетричні ризики).
 *
 * <p>Фірмовий колір: {@code #f38120} (темно-помаранчевий RAMOS/CONTEG) — застосовується до
 * рамок заголовків через CSS-класи, визначені в основному CSS-блоці NOCZvit.
 */
@Slf4j
public class RamosTrapSection {

    /**
     * Результат рендеру однієї секції трапів RAMOS.
     *
     * @param html      повний HTML-фрагмент (порожній рядок, якщо подій немає)
     * @param plainText текстовий блок лише з подіями {@link RamosTrapEvent#CLAUDE_STATES} для
     *                  Claude; порожній, якщо таких немає
     */
    public record SectionResult(String html, String plainText) {
        /** {@code true}, якщо рендерити нічого — подій немає. */
        public boolean isEmpty() {
            return html.isBlank();
        }
    }

    /**
     * Будує HTML-секцію та текстовий блок для Claude з переданого списку подій.
     *
     * @param events список подій RAMOS (може бути порожнім; не повинен бути null)
     * @return результат із заповненими html і plainText; порожній результат, якщо список порожній
     */
    public SectionResult build(List<RamosTrapEvent> events) {
        if (events.isEmpty()) {
            return new SectionResult("", "");
        }

        // Групуємо за залом; "Інші" завжди останні, решта залів — в алфавітному порядку.
        Map<String, List<RamosTrapEvent>> byRoom = events.stream()
                .sorted(Comparator.comparing(RamosTrapEvent::timestamp))
                .collect(Collectors.groupingBy(
                        RamosTrapEvent::room,
                        () -> new TreeMap<>((a, b) -> {
                            if ("Інші".equals(a) && !"Інші".equals(b)) return 1;
                            if (!"Інші".equals(a) && "Інші".equals(b)) return -1;
                            return a.compareTo(b);
                        }),
                        Collectors.toList()));

        StringBuilder html = new StringBuilder();
        html.append("<h2 class=\"ramos-title\">Ramos — події станом на ")
            .append(DateUtils.formatUa(Instant.now()))
            .append("</h2>\n");

        StringBuilder plainText = new StringBuilder();

        // Нумерація наскрізна через усі кімнати: таблиці тут ділять один потік подій одного
        // контролера, тож окремий відлік у кожній кімнаті лише заважав би зіставляти події.
        int n = 0;

        for (Map.Entry<String, List<RamosTrapEvent>> entry : byRoom.entrySet()) {
            String room = entry.getKey();
            List<RamosTrapEvent> roomEvents = entry.getValue();

            html.append("<div class=\"section\">\n")
                .append("<h3 class=\"ramos-room\">").append(StringEscapeUtils.escapeHtml4(room)).append("</h3>\n")
                .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                .append("<thead><tr>")
                .append("<th style=\"width:30px\">№</th>")
                .append("<th class=\"nw\">Час</th>")
                .append("<th class=\"nw\">Стан</th>")
                .append("<th>Назва датчика</th>")
                .append("<th>Тип датчика</th>")
                .append("</tr></thead><tbody>\n");

            for (RamosTrapEvent ev : roomEvents) {
                boolean forwardToClaude = RamosTrapEvent.CLAUDE_STATES.contains(ev.state());

                html.append("<tr>")
                    .append("<td>").append(++n).append(".</td>")
                    .append("<td class=\"nw\">").append(DateUtils.formatUa(ev.timestamp())).append("</td>")
                    .append("<td class=\"nw\"><b>").append(StringEscapeUtils.escapeHtml4(ev.state())).append("</b></td>")
                    .append("<td>").append(StringEscapeUtils.escapeHtml4(ev.sensorName())).append("</td>")
                    .append("<td>").append(StringEscapeUtils.escapeHtml4(ev.sensorType())).append("</td>")
                    .append("</tr>\n");

                if (forwardToClaude) {
                    plainText.append(DateUtils.formatUa(ev.timestamp()))
                             .append(" ").append(ev.state())
                             .append(" / ").append(ev.sensorName())
                             .append("\n");
                }
            }

            html.append("</tbody></table>\n</div>\n");
        }

        // Не "критичні події": з 1.26.0 сюди потрапляє й Warning-рівень для зростання
        // температури (High Warning) — див. RamosTrapEvent.CLAUDE_STATES.
        String plainResult = plainText.isEmpty() ? ""
                : "Ramos події, що потребують уваги:\n" + plainText;

        log.info("RamosTrapSection: {} event(s) rendered across {} room(s)",
                events.size(), byRoom.size());
        return new SectionResult(html.toString(), plainResult);
    }
}
