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
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Status;
import net.ukrcom.noczvit.zabbix.Client;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Презентаційний шар: перетворює список {@link Incident} на HTML-секцію інцидентів.
 *
 * <p>Інциденти з непорожнім ключем {@code inReplyTo} об'єднуються в пари: найраніший
 * START і найпізніший END з однаковим ключем відображаються одним рядком з колонками
 * Початок / Закінчення / Тривалість. Непарні інциденти (порожній ключ або наявна лише
 * одна сторона) відображаються з "—" у відсутніх колонках.
 */
@Slf4j
public class IncidentSectionBuilder {

    /** Створює білдер. Без стану — безпечно повторно використовувати між викликами. */
    public IncidentSectionBuilder() {
    }

    /**
     * Будує повну HTML-секцію інцидентів за вказану чергову зміну.
     *
     * @param allIncidents усі розібрані інциденти (можуть охоплювати кілька чергових змін)
     * @param zabbix клієнт Zabbix для графіків Ping; null, щоб пропустити графіки
     * @param dutyBegin початок чергової зміни для відображення
     * @param dutyEnd кінець чергової зміни для відображення
     * @return HTML-фрагмент (ніколи не null)
     */
    public String build(List<Incident> allIncidents, Client zabbix,
                        LocalDateTime dutyBegin, LocalDateTime dutyEnd) {
        return build(allIncidents, zabbix, dutyBegin, dutyEnd, null);
    }

    /**
     * Будує повну HTML-секцію з опціональним попередньо відрендереним блоком підсумку від AI.
     *
     * @param allIncidents усі розібрані інциденти (можуть охоплювати кілька чергових змін)
     * @param zabbix       клієнт Zabbix для графіків Ping; null, щоб пропустити графіки
     * @param dutyBegin    початок чергової зміни для відображення
     * @param dutyEnd      кінець чергової зміни для відображення
     * @param summaryHtml  HTML-фрагмент підсумку від Claude AI, або {@code null}, щоб не додавати
     * @return HTML-фрагмент (ніколи не null)
     */
    public String build(List<Incident> allIncidents, Client zabbix,
                        LocalDateTime dutyBegin, LocalDateTime dutyEnd, String summaryHtml) {
        long ctDutyBegin = dutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctDutyEnd = dutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        StringBuilder html = new StringBuilder();
        html.append("<p><h1>Інциденти, <u>зареєстровані в автоматичному режимі</u> системами Zabbix та OSM,<br>")
                .append("що відбувалися в період з ").append(DateUtils.formatUa(dutyBegin))
                .append(" по ").append(DateUtils.formatUa(dutyEnd))
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

        // Групуємо вже спарені рядки за локацією; явний LinkedHashMap як фабрика мапи зберігає
        // порядок першої появи локації серед інцидентів, а не алфавітний чи хеш-порядок.
        Map<String, List<IncidentRow>> byLocation = pairIncidents(incidents).stream()
                .collect(Collectors.groupingBy(IncidentRow::location, LinkedHashMap::new, Collectors.toList()));

        AtomicInteger n = new AtomicInteger(0);
        // Для кожної локації рендеримо окрему HTML-секцію: заголовок з таблицею, рядки
        // інцидентів, опційні графіки Ping (якщо задано zabbix), і закриваємо таблицю/секцію.
        byLocation.forEach((location, group) -> {
            html.append("<div class=\"section\">\n")
                    .append("<h2>Зареєстровані інциденти на виносі ")
                    .append(StringEscapeUtils.escapeHtml4(location)).append("</h2>\n")
                    .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                    .append("<thead><tr>")
                    .append("<th style=\"width:30px\">№</th>")
                    .append("<th class=\"nw\">Початок</th>")
                    .append("<th class=\"nw\">Закінчення</th>")
                    .append("<th class=\"nw\">Тривалість</th>")
                    .append("<th>Інцидент</th>")
                    .append("<th class=\"nw\">Обладнання</th>")
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
     * Групує інциденти за ключем {@code inReplyTo} у парні рядки, потім сортує за часом початку.
     * Інциденти з порожнім ключем розміщуються кожен у власному рядку.
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
            if (start == null && end == null) {
                // Група містить лише інциденти зі статусом NONE (напр. перезавантаження обладнання) — кожен своїм рядком
                group.forEach(i -> rows.add(new IncidentRow(i, null)));
            } else {
                rows.add(new IncidentRow(start, end));
            }
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
     * Рендерить один рядок таблиці ({@code <tr>}) для заданої пари інцидентів.
     * Парні рядки (наявні і start, і end) показують об'єднаний опис зі словом
     * "інцидент" та обчисленою тривалістю. Непарні рядки зберігають оригінальний
     * опис і показують "—" у відсутніх колонках.
     */
    private String buildRow(IncidentRow row, AtomicInteger n) {
        boolean paired = row.start() != null && row.end() != null;
        Incident primary = row.start() != null ? row.start() : row.end();


        // messageDateStr — це сирий заголовок Date: листа, тобто недовірене джерело нарівні з
        // темою й тілом: строгий парсер на ньому падає, але fallback на getSentDate() пропускає
        // лист далі разом із будь-яким хвостом після зони. Екрануємо так само, як сусідні комірки.
        String startCell = row.start() != null
                ? StringEscapeUtils.escapeHtml4(row.start().messageDateStr()) : "—";
        String endCell = row.end() != null
                ? StringEscapeUtils.escapeHtml4(row.end().messageDateStr()) : "—";
        String durationCell = paired
                ? DurationFormat.humanize(row.end().messageTs() - row.start().messageTs())
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

        String device = StringEscapeUtils.escapeHtml4(row.device());
        return "<tr>"
                + "<td>" + n.incrementAndGet() + ".</td>"
                + "<td class=\"nw\">" + startCell + "</td>"
                + "<td class=\"nw\">" + endCell + "</td>"
                + "<td class=\"nw\">" + durationCell + "</td>"
                + "<td>" + descHtml + "</td>"
                + "<td class=\"nw\">" + device + "</td>"
                + "</tr>\n";
    }

    /**
     * Додає вбудовані PNG-графіки Ping для всіх унікальних пристроїв у {@code rows}.
     * Кожен графік отримується паралельно через віртуальні потоки й вбудовується як base64 data URI.
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

    /** Утримує парний або наполовину наявний інцидент для відображення одним рядком таблиці. */
    private record IncidentRow(Incident start, Incident end) {

        String location() {
            return (start != null ? start : end).location();
        }

        long sortKey() {
            Incident primary = start != null ? start : end;
            return primary != null ? primary.messageTs() : 0L;
        }

        String device() {
            return (start != null ? start : end).device();
        }

        /** Об'єднує назви на перегляд з обох сторін пари, без дублікатів. */
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
