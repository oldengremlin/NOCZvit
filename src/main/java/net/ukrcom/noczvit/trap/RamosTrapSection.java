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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders {@link RamosTrapEvent} objects into an HTML report section and a Claude-ready
 * plain-text block.
 *
 * <p>Events are grouped by room (Room1–Room4, then «Інші») and sorted by timestamp within
 * each group. Critical-level events receive a red row class ({@code ramos-crit}); warning-level
 * events receive an amber row class ({@code ramos-warn}). The plain-text output includes only
 * Critical-level events (Critical, High Critical, Low Critical) to avoid flooding the Claude
 * token budget.
 *
 * <p>Brand colour: {@code #f38120} (RAMOS/CONTEG dark orange) — applied to heading borders
 * via CSS classes defined in the main NOCZvit CSS block.
 */
@Slf4j
public class RamosTrapSection {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final Set<String> CRITICAL_STATES =
            Set.of("Critical", "High Critical", "Low Critical");

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
            .append(DT_FMT.format(Instant.now()))
            .append("</h2>\n");

        StringBuilder plainText = new StringBuilder();

        for (Map.Entry<String, List<RamosTrapEvent>> entry : byRoom.entrySet()) {
            String room = entry.getKey();
            List<RamosTrapEvent> roomEvents = entry.getValue();

            html.append("<div class=\"section\">\n")
                .append("<h3 class=\"ramos-room\">").append(htmlEscape(room)).append("</h3>\n")
                .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                .append("<thead><tr>")
                .append("<th>Час</th>")
                .append("<th>Стан</th>")
                .append("<th>Назва датчика</th>")
                .append("<th>Тип датчика</th>")
                .append("</tr></thead><tbody>\n");

            for (RamosTrapEvent ev : roomEvents) {
                boolean critical = CRITICAL_STATES.contains(ev.state());
                String rowClass = critical ? " class=\"ramos-crit\"" : " class=\"ramos-warn\"";

                html.append("<tr").append(rowClass).append(">")
                    .append("<td>").append(TIME_FMT.format(ev.timestamp())).append("</td>")
                    .append("<td><b>").append(htmlEscape(ev.state())).append("</b></td>")
                    .append("<td>").append(htmlEscape(ev.sensorName())).append("</td>")
                    .append("<td>").append(htmlEscape(ev.sensorType())).append("</td>")
                    .append("</tr>\n");

                if (critical) {
                    plainText.append(TIME_FMT.format(ev.timestamp()))
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

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
