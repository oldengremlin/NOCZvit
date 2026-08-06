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
 * Renders {@link RamosTrapEvent} objects into an HTML report section and a Claude-ready
 * plain-text block.
 *
 * <p>Events are grouped by room (Room1–Room4, then «Інші») and sorted by timestamp within
 * each group. All rows use the neutral alternating background shared by every report table.
 * The plain-text output includes only
 * Critical-level events (Critical, High Critical, Low Critical) to avoid flooding the Claude
 * token budget.
 *
 * <p>Brand colour: {@code #f38120} (RAMOS/CONTEG dark orange) — applied to heading borders
 * via CSS classes defined in the main NOCZvit CSS block.
 */
@Slf4j
public class RamosTrapSection {

    /**
     * Rendered output for one RAMOS trap section.
     *
     * @param html      full HTML fragment (empty string when there are no events)
     * @param plainText plain-text block with only Critical-level events for Claude; empty when none
     */
    public record SectionResult(String html, String plainText) {
        /** {@code true} when there are no events to render. */
        public boolean isEmpty() {
            return html.isBlank();
        }
    }

    /**
     * Builds the HTML section and Claude plain-text block from the given event list.
     *
     * @param events list of RAMOS events (may be empty; must not be null)
     * @return result with both html and plainText populated; empty result when list is empty
     */
    public SectionResult build(List<RamosTrapEvent> events) {
        if (events.isEmpty()) {
            return new SectionResult("", "");
        }

        // Group by room; "Інші" always last, rooms in alphabetical order otherwise.
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
                boolean critical = RamosTrapEvent.CRITICAL_STATES.contains(ev.state());

                html.append("<tr>")
                    .append("<td>").append(++n).append(".</td>")
                    .append("<td class=\"nw\">").append(DateUtils.formatUa(ev.timestamp())).append("</td>")
                    .append("<td class=\"nw\"><b>").append(StringEscapeUtils.escapeHtml4(ev.state())).append("</b></td>")
                    .append("<td>").append(StringEscapeUtils.escapeHtml4(ev.sensorName())).append("</td>")
                    .append("<td>").append(StringEscapeUtils.escapeHtml4(ev.sensorType())).append("</td>")
                    .append("</tr>\n");

                if (critical) {
                    plainText.append(DateUtils.formatUa(ev.timestamp()))
                             .append(" ").append(ev.state())
                             .append(" / ").append(ev.sensorName())
                             .append("\n");
                }
            }

            html.append("</tbody></table>\n</div>\n");
        }

        String plainResult = plainText.isEmpty() ? ""
                : "Ramos критичні події:\n" + plainText;

        log.info("RamosTrapSection: {} event(s) rendered across {} room(s)",
                events.size(), byRoom.size());
        return new SectionResult(html.toString(), plainResult);
    }
}
