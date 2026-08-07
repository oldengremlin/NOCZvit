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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;

/**
 * Тести {@link IncidentSectionBuilder}: пейринг START/END за {@code inReplyTo}, заміна тексту
 * опису для схлопнутих пар, HTML-екранування ненадійних полів (включно з {@code messageDateStr}),
 * nowrap-класи колонок, нумерація рядків та фільтрація за межами duty-періоду.
 *
 * <p>Усюди передається {@code zabbix = null}, що вимикає добудову Ping-графіків (Javadoc параметра:
 * "null to skip graphs").
 */
class IncidentSectionBuilderTest {

    private static final LocalDateTime DUTY_BEGIN = LocalDateTime.of(2026, 8, 1, 8, 0, 0);
    private static final LocalDateTime DUTY_END = LocalDateTime.of(2026, 8, 1, 20, 0, 0);

    private final IncidentSectionBuilder builder = new IncidentSectionBuilder();

    private static long epoch(LocalDateTime dt) {
        return dt.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private static Incident incident(String location, String device, long messageTs, String messageDateStr,
            Status status, String description, List<String> reviewNames, String inReplyTo) {
        return new Incident(location, device, messageTs, messageTs, messageDateStr, messageDateStr,
                Source.PD, status, description, reviewNames, inReplyTo);
    }

    // ---- Пейринг START/END за inReplyTo ----

    @Test
    @DisplayName("Пара START+END з однаковим inReplyTo схлопується в один рядок з обома датами і тривалістю")
    void pairsStartAndEnd_intoSingleRowWithBothTimesAndDuration() {
        long tsStart = epoch(DUTY_BEGIN.plusHours(1));
        long tsEnd = epoch(DUTY_BEGIN.plusHours(1).plusMinutes(30));
        Incident start = incident("Обухів", "sw1", tsStart, "01.08.2026 09:00", Status.START,
                "sw1, Обухів, початок інциденту, втрата зв'язку", List.of(), "key-1");
        Incident end = incident("Обухів", "sw1", tsEnd, "01.08.2026 09:30", Status.END,
                "sw1, Обухів, кінець інциденту, втрата зв'язку", List.of(), "key-1");

        String html = builder.build(List.of(start, end), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("01.08.2026 09:00"));
        assertTrue(html.contains("01.08.2026 09:30"));
        assertTrue(html.contains(DurationFormat.humanize(tsEnd - tsStart)));
        // Один рядок таблиці -> лише один <tr> у тілі (тег <thead> також містить свій <tr>, тому
        // рахуємо лише в межах <tbody>).
        assertEquals(1, countDataRows(html));
    }

    @Test
    @DisplayName("Схлопнена пара: опис не містить 'початок'/'кінець', лише 'інцидент,'")
    void pairedRow_descriptionReplacesStartEndWordingWithGenericIncident() {
        long tsStart = epoch(DUTY_BEGIN.plusHours(1));
        long tsEnd = epoch(DUTY_BEGIN.plusHours(2));
        // Опис старту навмисно містить обидві фрази, щоб перевірити обидва .replace() у ланцюжку.
        Incident start = incident("Обухів", "sw1", tsStart, "01.08.2026 09:00", Status.START,
                "sw1, Обухів, початок інциденту, втрата зв'язку; кінець інциденту, буде замінено теж",
                List.of(), "key-1");
        Incident end = incident("Обухів", "sw1", tsEnd, "01.08.2026 10:00", Status.END,
                "sw1, Обухів, кінець інциденту, втрата зв'язку", List.of(), "key-1");

        String html = builder.build(List.of(start, end), null, DUTY_BEGIN, DUTY_END);

        assertFalse(html.contains("початок інциденту"));
        assertFalse(html.contains("кінець інциденту"));
        assertTrue(html.contains("інцидент, втрата зв'язку"));
    }

    @Test
    @DisplayName("Без пари (лише START) — '—' на місці Закінчення і Тривалості")
    void unpairedStart_showsEmDashForEndAndDuration() {
        long ts = epoch(DUTY_BEGIN.plusHours(1));
        Incident start = incident("Обухів", "sw1", ts, "01.08.2026 09:00", Status.START,
                "sw1, Обухів, початок інциденту, втрата зв'язку", List.of(), "");

        String html = builder.build(List.of(start), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("01.08.2026 09:00"));
        // Дві комірки з "—": Закінчення і Тривалість
        assertEquals(2, countOccurrences(html, ">—<"));
        // Опис лишається незмінним (пари немає -> заміна "початок"/"кінець" не відбувається)
        assertTrue(html.contains("початок інциденту, втрата зв'язку"));
    }

    // ---- Екранування ----

    @Test
    @DisplayName("Небезпечні символи в messageDateStr екрануються, сирий <script> у вивід не потрапляє")
    void messageDateStr_withScriptTag_isEscaped() {
        long ts = epoch(DUTY_BEGIN.plusHours(1));
        Incident start = incident("Обухів", "sw1", ts, "<script>alert(1)</script>", Status.START,
                "опис", List.of(), "");

        String html = builder.build(List.of(start), null, DUTY_BEGIN, DUTY_END);

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("Небезпечні символи в description/location/device/reviewNames екрануються в HTML-сутності")
    void dangerousCharactersInFields_areEscapedAsHtmlEntities() {
        long ts = epoch(DUTY_BEGIN.plusHours(1));
        Incident start = incident("<b>Лок</b> & \"Точка\"", "dev<1>", ts, "01.08.2026 09:00", Status.START,
                "опис <img src=x onerror=alert(1)> & \"info\"", List.of("Нев\"ірна<назва>"), "");

        String html = builder.build(List.of(start), null, DUTY_BEGIN, DUTY_END);

        assertFalse(html.contains("<b>Лок</b>"));
        assertFalse(html.contains("dev<1>"));
        assertFalse(html.contains("<img src=x"));
        assertFalse(html.contains("Нев\"ірна<назва>"));

        assertTrue(html.contains("&lt;b&gt;Лок&lt;/b&gt;"));
        assertTrue(html.contains("dev&lt;1&gt;"));
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"));
        assertTrue(html.contains("&amp;"));
        assertTrue(html.contains("&quot;"));
    }

    // ---- nowrap-класи ----

    @Test
    @DisplayName("Колонки Початок/Закінчення/Тривалість/Обладнання мають class=\"nw\" у <th>")
    void headerColumns_haveNowrapClass() {
        long ts = epoch(DUTY_BEGIN.plusHours(1));
        Incident start = incident("Обухів", "sw1", ts, "01.08.2026 09:00", Status.START, "опис", List.of(), "");

        String html = builder.build(List.of(start), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("<th class=\"nw\">Початок</th>"));
        assertTrue(html.contains("<th class=\"nw\">Закінчення</th>"));
        assertTrue(html.contains("<th class=\"nw\">Тривалість</th>"));
        assertTrue(html.contains("<th class=\"nw\">Обладнання</th>"));
    }

    // ---- Нумерація ----

    @Test
    @DisplayName("Колонка № має width:30px inline-style, нумерація рядків послідовна")
    void rowNumbering_isSequentialWithWidthStyleHeader() {
        long ts1 = epoch(DUTY_BEGIN.plusHours(1));
        long ts2 = epoch(DUTY_BEGIN.plusHours(2));
        long ts3 = epoch(DUTY_BEGIN.plusHours(3));
        Incident i1 = incident("Обухів", "sw1", ts1, "01.08.2026 09:00", Status.START, "опис1", List.of(), "");
        Incident i2 = incident("Обухів", "sw2", ts2, "01.08.2026 10:00", Status.START, "опис2", List.of(), "");
        Incident i3 = incident("Обухів", "sw3", ts3, "01.08.2026 11:00", Status.START, "опис3", List.of(), "");

        String html = builder.build(List.of(i1, i2, i3), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("<th style=\"width:30px\">№</th>"));
        int idx1 = html.indexOf("<td>1.</td>");
        int idx2 = html.indexOf("<td>2.</td>");
        int idx3 = html.indexOf("<td>3.</td>");
        assertTrue(idx1 >= 0 && idx2 > idx1 && idx3 > idx2);
    }

    // ---- Фільтрація за межами duty-періоду ----

    @Test
    @DisplayName("Фільтрація за duty-періодом: межі включно (>=, <=)")
    void filtering_dutyPeriodBoundsAreInclusive() {
        long ctBegin = epoch(DUTY_BEGIN);
        long ctEnd = epoch(DUTY_END);
        Incident atBegin = incident("Обухів", "sw1", ctBegin, "at-begin", Status.START, "опис", List.of(), "");
        Incident atEnd = incident("Обухів", "sw2", ctEnd, "at-end", Status.START, "опис", List.of(), "");

        String html = builder.build(List.of(atBegin, atEnd), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("at-begin"));
        assertTrue(html.contains("at-end"));
        assertEquals(2, countDataRows(html));
    }

    @Test
    @DisplayName("Фільтрація за duty-періодом: інциденти строго поза вікном виключаються")
    void filtering_incidentsOutsideDutyPeriodAreExcluded() {
        long beforeBegin = epoch(DUTY_BEGIN) - 1;
        long afterEnd = epoch(DUTY_END) + 1;
        Incident before = incident("Обухів", "sw1", beforeBegin, "before", Status.START, "опис", List.of(), "");
        Incident after = incident("Обухів", "sw2", afterEnd, "after", Status.START, "опис", List.of(), "");

        String html = builder.build(List.of(before, after), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("Інцидентів не зареєстровано"));
        assertFalse(html.contains("before"));
        assertFalse(html.contains("after"));
    }

    @Test
    @DisplayName("Фільтрація: інцидент усередині вікна лишається, інцидент поза вікном виключається")
    void filtering_mixedInsideAndOutsideIncidents() {
        long inside = epoch(DUTY_BEGIN.plusHours(1));
        long outside = epoch(DUTY_END) + 3600;
        Incident in = incident("Обухів", "sw1", inside, "inside-marker", Status.START, "опис", List.of(), "");
        Incident out = incident("Обухів", "sw2", outside, "outside-marker", Status.START, "опис", List.of(), "");

        String html = builder.build(List.of(in, out), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("inside-marker"));
        assertFalse(html.contains("outside-marker"));
    }

    // ---- zabbix == null пропускає Ping-графіки без винятку ----

    @Test
    @DisplayName("zabbix == null: побудова не кидає виняток і не додає графіків")
    void nullZabbixClient_skipsGraphsWithoutError() {
        long ts = epoch(DUTY_BEGIN.plusHours(1));
        Incident start = incident("Обухів", "sw1", ts, "01.08.2026 09:00", Status.START, "опис", List.of(), "");

        String html = builder.build(List.of(start), null, DUTY_BEGIN, DUTY_END);

        assertTrue(html.contains("sw1"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Рахує рядки {@code <tr>} лише в межах {@code <tbody>}, ігноруючи заголовний {@code <tr>} у {@code <thead>}. */
    private static int countDataRows(String html) {
        int bodyStart = html.indexOf("<tbody>");
        assertTrue(bodyStart >= 0, "html має містити <tbody>");
        return countOccurrences(html.substring(bodyStart), "<tr>");
    }
}
