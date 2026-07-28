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
package net.ukrcom.noczvit.report;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.NOCZvit;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.zabbix.Client;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Presentation: converts a list of {@link Incident} objects into the HTML incidents section.
 */
@Slf4j
public class IncidentSectionBuilder {

    public IncidentSectionBuilder() {}

    /**
     * Builds the full HTML section for incidents in the given duty period.
     *
     * @param allIncidents all parsed incidents (may span multiple duty periods)
     * @param zabbix       Zabbix client for Ping graphs; null to skip graphs
     * @param dutyBegin    start of the duty period to display
     * @param dutyEnd      end of the duty period to display
     * @return HTML fragment (never null)
     */
    public String build(List<Incident> allIncidents, Client zabbix,
                        LocalDateTime dutyBegin, LocalDateTime dutyEnd) {
        long ctDutyBegin = dutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctDutyEnd   = dutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        StringBuilder html = new StringBuilder();
        html.append("<p><h1>Інциденти, <u>зареєстровані в автоматичному режимі</u> системами Zabbix та OSM,<br>")
                .append("що відбувалися в період з ").append(dutyBegin.format(NOCZvit.DATE_TIME_FORMATTER))
                .append(" по ").append(dutyEnd.format(NOCZvit.DATE_TIME_FORMATTER))
                .append("</h1>\n");

        List<Incident> incidents = allIncidents.stream()
                .filter(i -> i.messageTs() >= ctDutyBegin && i.messageTs() <= ctDutyEnd)
                .sorted(Comparator.comparingLong(Incident::messageTs).thenComparingLong(Incident::eventTs))
                .toList();

        if (incidents.isEmpty()) {
            html.append("<p><i>Інцидентів не зареєстровано</i>\n<p>");
            return html.toString();
        }

        Map<String, List<Incident>> byLocation = incidents.stream()
                .collect(Collectors.groupingBy(Incident::location, LinkedHashMap::new, Collectors.toList()));

        AtomicInteger n = new AtomicInteger(0);
        byLocation.forEach((location, group) -> {
            html.append("<div class=\"section\">\n")
                    .append("<h2>Зареєстровані інциденти на виносі ").append(location).append("</h2>\n")
                    .append("<table width=\"75%\" cellspacing=\"0\" cellpadding=\"0\">")
                    .append("<thead><tr>")
                    .append("<th style=\"width:30px\">№</th>")
                    .append("<th>Дата та час</th>")
                    .append("<th>Інцидент</th>")
                    .append("<th>Обладнання</th>")
                    .append("</tr></thead><tbody>\n");

            group.forEach(inc -> html.append(buildRow(inc, n)));

            if (zabbix != null) {
                appendPingGraphs(html, group, zabbix, dutyBegin, dutyEnd);
            }

            html.append("</tbody></table>\n</div>\n");
        });

        html.append("<p>");
        return html.toString();
    }

    private String buildRow(Incident inc, AtomicInteger n) {
        String rowClass = switch (inc.status()) {
            case START -> " class=\"row-start\"";
            case END   -> " class=\"row-end\"";
            case NONE  -> "";
        };

        String descHtml = StringEscapeUtils.escapeHtml4(inc.description());
        if (!inc.reviewNames().isEmpty()) {
            String names = inc.reviewNames().stream()
                    .map(StringEscapeUtils::escapeHtml4)
                    .collect(Collectors.joining("</b>' та '<b>"));
            descHtml += " (<i>потребує коригування назви</i> '<b>" + names + "</b>')";
        }

        String device = inc.device().isEmpty() ? "" : inc.device();
        return "<tr" + rowClass + ">"
                + "<td>" + n.incrementAndGet() + ".</td>"
                + "<td>" + inc.messageDateStr() + "</td>"
                + "<td>" + descHtml + "</td>"
                + "<td>" + device + "</td>"
                + "</tr>\n";
    }

    private void appendPingGraphs(StringBuilder html, List<Incident> group,
                                  Client zabbix, LocalDateTime from, LocalDateTime to) {
        List<String> pingDevices = group.stream()
                .map(Incident::device)
                .filter(d -> !d.isEmpty())
                .distinct()
                .toList();
        if (pingDevices.isEmpty()) {
            return;
        }
        try (var pingExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<String>> futures = pingDevices.stream()
                    .map(device -> CompletableFuture.supplyAsync(
                            () -> zabbix.getPingGraphRow(device, from, to), pingExecutor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            futures.forEach(f -> {
                try {
                    html.append(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ignored) {
                }
            });
        }
    }
}
