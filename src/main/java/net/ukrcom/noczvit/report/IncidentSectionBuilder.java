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
import java.util.ArrayList;
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
import net.ukrcom.noczvit.model.Incident.Status;
import net.ukrcom.noczvit.zabbix.Client;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Presentation: converts a list of {@link Incident} objects into the HTML
 * incidents section.
 *
 * <p>Incidents with a non-empty {@code inReplyTo} key are paired: the earliest
 * START and the latest END sharing the same key are displayed as a single row
 * with Початок / Закінчення / Тривалість columns. Unpaired incidents (empty
 * key, or only one side present) are displayed with "—" in the missing columns.
 */
@Slf4j
public class IncidentSectionBuilder {

    /** Creates the builder. Stateless — safe to reuse across calls. */
    public IncidentSectionBuilder() {
    }

    /**
     * Builds the full HTML section for incidents in the given duty period.
     *
     * @param allIncidents all parsed incidents (may span multiple duty periods)
     * @param zabbix Zabbix client for Ping graphs; null to skip graphs
     * @param dutyBegin start of the duty period to display
     * @param dutyEnd end of the duty period to display
     * @return HTML fragment (never null)
     */
    public String build(List<Incident> allIncidents, Client zabbix,
                        LocalDateTime dutyBegin, LocalDateTime dutyEnd) {
        return build(allIncidents, zabbix, dutyBegin, dutyEnd, null);
    }

    /**
     * Builds the full HTML section with an optional pre-rendered AI summary block.
     *
     * @param allIncidents all parsed incidents (may span multiple duty periods)
     * @param zabbix       Zabbix client for Ping graphs; null to skip graphs
     * @param dutyBegin    start of the duty period to display
     * @param dutyEnd      end of the duty period to display
     * @param summaryHtml  Claude AI summary HTML fragment, or {@code null} to omit it
     * @return HTML fragment (never null)
     */
    public String build(List<Incident> allIncidents, Client zabbix,
                        LocalDateTime dutyBegin, LocalDateTime dutyEnd, String summaryHtml) {
        long ctDutyBegin = dutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctDutyEnd = dutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        StringBuilder html = new StringBuilder();
        html.append("<p><h1>Інциденти, <u>зареєстровані в автоматичному режимі</u> системами Zabbix та OSM,<br>")
                .append("що відбувалися в період з ").append(dutyBegin.format(NOCZvit.DATE_TIME_FORMATTER))
                .append(" по ").append(dutyEnd.format(NOCZvit.DATE_TIME_FORMATTER))
                .append("</h1>\n");

        if (summaryHtml != null && !summaryHtml.isBlank()) {
            html.append(summaryHtml);
        }

        List<Incident> incidents = allIncidents.stream()
                .filter(i -> i.messageTs() >= ctDutyBegin && i.messageTs() <= ctDutyEnd)
                .toList();

        if (incidents.isEmpty()) {
            html.append("<p><i>Інцидентів не зареєстровано</i>\n<p>");
            return html.toString();
        }

        List<IncidentRow> rows = pairIncidents(incidents);

        Map<String, List<IncidentRow>> byLocation = rows.stream()
                .collect(Collectors.groupingBy(IncidentRow::location, LinkedHashMap::new, Collectors.toList()));

        AtomicInteger n = new AtomicInteger(0);
        byLocation.forEach((location, group) -> {
            html.append("<div class=\"section\">\n")
                    .append("<h2>Зареєстровані інциденти на виносі ").append(location).append("</h2>\n")
                    .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                    .append("<thead><tr>")
                    .append("<th style=\"width:30px\">№</th>")
                    .append("<th>Початок</th>")
                    .append("<th>Закінчення</th>")
                    .append("<th>Тривалість</th>")
                    .append("<th>Інцидент</th>")
                    .append("<th>Обладнання</th>")
                    .append("</tr></thead><tbody>\n");

            group.forEach(row -> html.append(buildRow(row, n)));

            if (zabbix != null) {
                appendPingGraphs(html, group, zabbix, dutyBegin, dutyEnd);
            }

            html.append("</tbody></table>\n</div>\n");
        });

        html.append("<p>");
        return html.toString();
    }

    /**
     * Groups incidents by {@code inReplyTo} key into paired rows, then sorts by start time.
     * Incidents with an empty key are each placed in their own row.
     */
    private List<IncidentRow> pairIncidents(List<Incident> incidents) {
        Map<String, List<Incident>> byKey = new LinkedHashMap<>();
        List<Incident> unkeyed = new ArrayList<>();

        for (Incident i : incidents) {
            if (i.inReplyTo() != null && !i.inReplyTo().isBlank()) {
                byKey.computeIfAbsent(i.inReplyTo(), k -> new ArrayList<>()).add(i);
            } else {
                unkeyed.add(i);
            }
        }

        List<IncidentRow> rows = new ArrayList<>();

        for (List<Incident> group : byKey.values()) {
            Incident start = group.stream()
                    .filter(i -> i.status() == Status.START)
                    .min(Comparator.comparingLong(Incident::messageTs))
                    .orElse(null);
            Incident end = group.stream()
                    .filter(i -> i.status() == Status.END)
                    .max(Comparator.comparingLong(Incident::messageTs))
                    .orElse(null);
            rows.add(new IncidentRow(start, end));
        }

        for (Incident i : unkeyed) {
            if (i.status() == Status.END) {
                rows.add(new IncidentRow(null, i));
            } else {
                rows.add(new IncidentRow(i, null));
            }
        }

        rows.sort(Comparator.comparingLong(IncidentRow::sortKey));
        return rows;
    }

    /**
     * Renders a single table row ({@code <tr>}) for the given incident pair.
     * Paired rows (both start and end present) show the combined description with
     * "інцидент" wording and the computed duration. Unpaired rows keep their
     * original description and show "—" in the missing columns.
     */
    private String buildRow(IncidentRow row, AtomicInteger n) {
        boolean paired = row.start() != null && row.end() != null;
        Incident primary = row.start() != null ? row.start() : row.end();

        String rowClass = paired ? "" : switch (primary.status()) {
            case START -> " class=\"row-start\"";
            case END -> " class=\"row-end\"";
            case NONE -> "";
        };

        String startCell = row.start() != null ? row.start().messageDateStr() : "—";
        String endCell = row.end() != null ? row.end().messageDateStr() : "—";
        String durationCell = paired
                ? formatDuration(row.end().messageTs() - row.start().messageTs())
                : "—";

        String rawDesc = paired
                ? primary.description()
                        .replace("початок інциденту, ", "інцидент, ")
                        .replace("кінець інциденту, ", "інцидент, ")
                : primary.description();
        String descHtml = StringEscapeUtils.escapeHtml4(rawDesc);

        List<String> reviewNames = row.mergedReviewNames();
        if (!reviewNames.isEmpty()) {
            String names = reviewNames.stream()
                    .map(StringEscapeUtils::escapeHtml4)
                    .collect(Collectors.joining("</b>' та '<b>"));
            descHtml += " (<i>потребує коригування назви</i> '<b>" + names + "</b>')";
        }

        String device = row.device().isEmpty() ? "" : row.device();
        return "<tr" + rowClass + ">"
                + "<td>" + n.incrementAndGet() + ".</td>"
                + "<td>" + startCell + "</td>"
                + "<td>" + endCell + "</td>"
                + "<td>" + durationCell + "</td>"
                + "<td>" + descHtml + "</td>"
                + "<td>" + device + "</td>"
                + "</tr>\n";
    }

    /**
     * Formats a duration in seconds to a human-readable Ukrainian string.
     * Durations under 60 s are shown as {@code "< 1 хв"}.
     */
    private String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        if (seconds < 60) return "< 1 хв";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " хв";
        long hours = minutes / 60;
        long mins = minutes % 60;
        return mins > 0 ? hours + " год " + mins + " хв" : hours + " год";
    }

    /**
     * Appends inline PNG Ping graphs for all distinct devices in {@code rows}.
     * Each graph is fetched concurrently via virtual threads and embedded as a base64 data URI.
     */
    private void appendPingGraphs(StringBuilder html, List<IncidentRow> rows,
                                  Client zabbix, LocalDateTime from, LocalDateTime to) {
        List<String> pingDevices = rows.stream()
                .map(IncidentRow::device)
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
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
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

    /** Holds a paired or half-present incident for display as a single table row. */
    private record IncidentRow(Incident start, Incident end) {

        String location() {
            return (start != null ? start : end).location();
        }

        long sortKey() {
            return (start != null ? start : end).messageTs();
        }

        String device() {
            return (start != null ? start : end).device();
        }

        List<String> mergedReviewNames() {
            List<String> names = new ArrayList<>();
            if (start != null) names.addAll(start.reviewNames());
            if (end != null) {
                for (String name : end.reviewNames()) {
                    if (!names.contains(name)) names.add(name);
                }
            }
            return names;
        }
    }
}
