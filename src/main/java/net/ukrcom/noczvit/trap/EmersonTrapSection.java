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
 * Рендерить список об'єктів {@link TrapIncident} у HTML-секцію та блок
 * текстового резюме для промпту Claude AI.
 */
public class EmersonTrapSection {

    /**
     * Результат {@link #build(List, List)}: HTML-фрагмент, текстовий блок для промпту AI,
     * та опціональний HTML PS-розділу зі списком нерозпізнаних типів трапів.
     *
     * @param html        HTML-фрагмент секції; порожній рядок, якщо інцидентів немає
     * @param plainText   звичайний текст для промпту Claude; порожній рядок, якщо інцидентів немає
     * @param unknownHtml HTML PS-розділу для нерозпізнаних типів трапів; порожній рядок, якщо їх немає
     */
    public record SectionResult(String html, String plainText, String unknownHtml) {

        public boolean isEmpty() {
            return html.isBlank();
        }
    }

    /** Створює білдер. Без стану — безпечно перевикористовувати між викликами. */
    public EmersonTrapSection() {
    }

    /**
     * Будує HTML секції, звичайний текст та HTML PS-розділу (нерозпізнані трапи).
     *
     * @param incidents    скорельовані інциденти трапів
     * @param unknownTraps сирі події трапів, що потрапили в catch-all і не мають відомого опису
     * @return {@link SectionResult}; ніколи не null
     */
    public SectionResult build(List<TrapIncident> incidents, List<TrapEvent> unknownTraps) {
        SectionResult base = build(incidents);
        String unknownHtml = (unknownTraps != null && !unknownTraps.isEmpty())
                ? buildUnknownHtml(unknownTraps) : "";
        return new SectionResult(base.html(), base.plainText(), unknownHtml);
    }

    /**
     * Будує HTML секції та звичайний текст із заданих інцидентів.
     * Повертає порожній результат, якщо список порожній.
     *
     * @param incidents скорельовані інциденти трапів
     * @return {@link SectionResult} з HTML та звичайним текстом; ніколи не null
     */
    public SectionResult build(List<TrapIncident> incidents) {
        if (incidents == null || incidents.isEmpty()) {
            return new SectionResult("", "", "");
        }

        // Сортування: спочатку ADC за алфавітом, потім PDC за алфавітом
        List<TrapIncident> sorted = incidents.stream()
                .sorted(Comparator
                        .comparing((TrapIncident i) -> i.deviceClass().equals(TrapEvent.CLASS_ADC) ? 0 : 1)
                        .thenComparing(TrapIncident::hostname)
                        .thenComparing(TrapIncident::activatedAt))
                .toList();

        // Групування за хостнеймом (зі збереженням порядку ADC-спочатку)
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

            // HTML-блок пристрою
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

            // Текстовий блок пристрою
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

                // Текстовий рядок
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

    /**
     * Будує HTML PS-розділу зі списком нерозпізнаних типів трапів, згрупованих за хостнеймом.
     */
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
