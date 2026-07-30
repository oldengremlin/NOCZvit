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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Renders a list of {@link TrapIncident} objects into an HTML section and a
 * plain-text summary block for the Claude AI prompt.
 */
public class EmersonTrapSection {

    private static final DateTimeFormatter HTML_FMT = DateTimeFormatter
            .ofPattern("dd.MM HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter TEXT_FMT = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    /**
     * Result of {@link #build(List)}: an HTML fragment and a plain-text block for the AI prompt.
     *
     * @param html      HTML section fragment; empty string when there are no incidents
     * @param plainText plain text for the Claude prompt; empty string when there are no incidents
     */
    public record SectionResult(String html, String plainText) {

        public boolean isEmpty() {
            return html.isBlank();
        }
    }

    /** Creates the builder. Stateless — safe to reuse across calls. */
    public EmersonTrapSection() {
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
            return new SectionResult("", "");
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
                    .append("<th style=\"width:155px\">Початок</th>")
                    .append("<th style=\"width:155px\">Закінчення</th>")
                    .append("<th style=\"width:100px\">Тривалість</th>")
                    .append("<th>Подія</th>")
                    .append("</tr></thead><tbody>\n");

            // Plain text device block
            text.append("\n[").append(hostname).append(" / ").append(ip).append("]\n");

            int n = 0;
            for (TrapIncident inc : devIncidents) {
                n++;
                String startStr = HTML_FMT.format(inc.activatedAt());
                String endStr = inc.clearedAt() != null ? HTML_FMT.format(inc.clearedAt()) : "—";
                String durStr = inc.clearedAt() != null
                        ? formatDuration(inc.activatedAt(), inc.clearedAt()) : "—";

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
                        .append("<td>").append(startStr).append("</td>")
                        .append("<td>").append(endStr).append("</td>")
                        .append("<td>").append(durStr).append("</td>")
                        .append("<td>").append(descHtml).append("</td>")
                        .append("</tr>\n");

                // Plain text row
                String startTextStr = TEXT_FMT.format(inc.activatedAt());
                String endTextStr = inc.clearedAt() != null ? TEXT_FMT.format(inc.clearedAt()) : "незакрито";
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

        return new SectionResult(html.toString(), text.toString());
    }

    private static String formatDuration(Instant from, Instant to) {
        long seconds = Duration.between(from, to).getSeconds();
        if (seconds < 0) {
            seconds = 0;
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return hours + " год " + minutes + " хв " + secs + " с";
        }
        if (minutes > 0) {
            return minutes + " хв " + secs + " с";
        }
        return secs + " с";
    }
}
