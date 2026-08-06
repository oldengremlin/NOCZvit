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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.report.DurationFormat;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Renders a list of {@link TrapIncident} objects into an HTML section and a
 * plain-text summary block for the Claude AI prompt.
 */
public class EmersonTrapSection {

    /**
     * Result of {@link #build(List, List)}: an HTML fragment, a plain-text block for the AI prompt,
     * and an optional PS section HTML listing unrecognized trap types.
     *
     * @param html        HTML section fragment; empty string when there are no incidents
     * @param plainText   plain text for the Claude prompt; empty string when there are no incidents
     * @param unknownHtml PS section HTML for unrecognized trap types; empty string when there are none
     */
    public record SectionResult(String html, String plainText, String unknownHtml) {

        public boolean isEmpty() {
            return html.isBlank();
        }
    }

    /** Creates the builder. Stateless — safe to reuse across calls. */
    public EmersonTrapSection() {
    }

    /**
     * Builds the section HTML, plain-text, and PS (unknown traps) HTML.
     *
     * @param incidents    correlated trap incidents
     * @param unknownTraps raw trap events that hit the catch-all and have no known description
     * @return {@link SectionResult}; never null
     */
    public SectionResult build(List<TrapIncident> incidents, List<TrapEvent> unknownTraps) {
        SectionResult base = build(incidents);
        String unknownHtml = (unknownTraps != null && !unknownTraps.isEmpty())
                ? buildUnknownHtml(unknownTraps) : "";
        return new SectionResult(base.html(), base.plainText(), unknownHtml);
    }

    /**
     * Builds the section HTML and plain-text from the given incidents.
     * Returns an empty result if the list is empty.
     *
     * @param incidents correlated trap incidents
     * @return {@link SectionResult} with HTML and plain text; never null
     */
    public SectionResult build(List<TrapIncident> incidents) {
        if (incidents == null || incidents.isEmpty()) {
            return new SectionResult("", "", "");
        }

        // Sort: ADC alphabetically first, then PDC alphabetically
        List<TrapIncident> sorted = incidents.stream()
                .sorted(Comparator
                        .comparing((TrapIncident i) -> i.deviceClass().equals(TrapEvent.CLASS_ADC) ? 0 : 1)
                        .thenComparing(TrapIncident::hostname)
                        .thenComparing(TrapIncident::activatedAt))
                .toList();

        // Group by hostname (preserving ADC-first order)
        Map<String, List<TrapIncident>> byDevice = sorted.stream()
                .collect(Collectors.groupingBy(TrapIncident::hostname, LinkedHashMap::new, Collectors.toList()));

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\">\n")
                .append("<h2 class=\"trap-title\">Зареєстровані події по ДБЖ та кондиціонерах Emerson на Датацентрі</h2>\n");

        StringBuilder text = new StringBuilder();
        text.append("=== ПОДІЇ ОБЛАДНАННЯ ДАТАЦЕНТРУ (ТІЛЬКИ ЦЯ ЗМІНА — НЕ ПЕРЕНОСИТИ В НАСТУПНІ) ===\n");

        byDevice.forEach((hostname, devIncidents) -> {
            TrapIncident first = devIncidents.get(0);
            String ip = first.ip();

            // HTML device block
            html.append("<h3 class=\"trap-device\"><a href=\"http://").append(StringEscapeUtils.escapeHtml4(ip))
                    .append("/\" style=\"color:#1b5e20\">").append(StringEscapeUtils.escapeHtml4(hostname))
                    .append("</a> (").append(StringEscapeUtils.escapeHtml4(ip)).append(")</h3>\n")
                    .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                    .append("<thead><tr>")
                    .append("<th style=\"width:30px\">№</th>")
                    .append("<th class=\"nw\">Початок</th>")
                    .append("<th class=\"nw\">Закінчення</th>")
                    .append("<th class=\"nw\">Тривалість</th>")
                    .append("<th>Подія</th>")
                    .append("</tr></thead><tbody>\n");

            // Plain text device block
            text.append("\n[").append(hostname).append(" / ").append(ip).append("]\n");

            int n = 0;
            for (TrapIncident inc : devIncidents) {
                n++;
                String startStr = DateUtils.formatUa(inc.activatedAt());
                String endStr = inc.clearedAt() != null ? DateUtils.formatUa(inc.clearedAt()) : "—";
                String durStr = inc.clearedAt() != null
                        ? DurationFormat.between(inc.activatedAt(), inc.clearedAt()) : "—";

                String descHtml = inc.severity() == TrapIncident.Severity.INFO
                        ? "<i>" + StringEscapeUtils.escapeHtml4(inc.description()) + "</i>"
                        : StringEscapeUtils.escapeHtml4(inc.description());

                if (!inc.details().isEmpty()) {
                    descHtml += "<br><small>" + inc.details().stream()
                            .map(StringEscapeUtils::escapeHtml4)
                            .collect(Collectors.joining("; ")) + "</small>";
                }

                html.append("<tr>")
                        .append("<td>").append(n).append(".</td>")
                        .append("<td class=\"nw\">").append(startStr).append("</td>")
                        .append("<td class=\"nw\">").append(endStr).append("</td>")
                        .append("<td class=\"nw\">").append(durStr).append("</td>")
                        .append("<td>").append(descHtml).append("</td>")
                        .append("</tr>\n");

                // Plain text row
                String startTextStr = DateUtils.formatUa(inc.activatedAt());
                String endTextStr = inc.clearedAt() != null ? DateUtils.formatUa(inc.clearedAt()) : "незакрито";
                text.append(n).append(". ").append(startTextStr)
                        .append(" – ").append(endTextStr)
                        .append(" | ").append(inc.description());
                if (!inc.details().isEmpty()) {
                    text.append(" [").append(String.join("; ", inc.details())).append("]");
                }
                text.append("\n");
            }

            html.append("</tbody></table>\n");
        });

        html.append("</div>\n");
        text.append("=== КІНЕЦЬ ПОДІЙ ОБЛАДНАННЯ ДАТАЦЕНТРУ ===\n");

        return new SectionResult(html.toString(), text.toString(), "");
    }

    private String buildUnknownHtml(List<TrapEvent> unknownTraps) {
        Map<String, List<TrapEvent>> byHost = unknownTraps.stream()
                .collect(Collectors.groupingBy(TrapEvent::hostname, LinkedHashMap::new, Collectors.toList()));

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\">\n")
                .append("<h2 class=\"trap-ps-title\">ps: нерозпізнані типи подій по ДБЖ та кондиціонерах Emerson:</h2>\n");

        byHost.forEach((hostname, evs) -> {
            TrapEvent first = evs.get(0);
            html.append("<h3 class=\"trap-ps-device\">")
                    .append(StringEscapeUtils.escapeHtml4(hostname))
                    .append(" (").append(StringEscapeUtils.escapeHtml4(first.ip())).append(")")
                    .append("</h3>\n")
                    .append("<ul class=\"trap-ps-list\">\n");
            evs.forEach(ev ->
                    html.append("<li>").append(StringEscapeUtils.escapeHtml4(ev.trapType()))
                            .append(" <small>(").append(DateUtils.formatUa(ev.timestamp())).append(")</small>")
                            .append("</li>\n"));
            html.append("</ul>\n");
        });

        html.append("</div>\n");
        return html.toString();
    }
}
