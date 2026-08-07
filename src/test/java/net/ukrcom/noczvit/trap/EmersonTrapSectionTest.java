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
import java.util.List;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.report.DurationFormat;
import net.ukrcom.noczvit.trap.EmersonTrapSection.SectionResult;
import net.ukrcom.noczvit.trap.TrapIncident.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmersonTrapSectionTest {

    private static final Instant T0 = Instant.parse("2026-08-07T10:00:00Z");

    private static TrapIncident incident(String deviceClass, String hostname, String ip, Severity severity,
            Instant activatedAt, Instant clearedAt, String description, List<String> details) {
        return new TrapIncident(deviceClass, hostname, ip, severity, activatedAt, clearedAt, description, details);
    }

    private static TrapIncident closedPdc(String hostname, Instant start, Instant end, String desc) {
        return incident(TrapEvent.CLASS_PDC, hostname, "10.0.0.1", Severity.ALARM, start, end, desc, List.of());
    }

    private final EmersonTrapSection section = new EmersonTrapSection();

    // =========================================================================================
    // Порожній вхід
    // =========================================================================================

    @Test
    void build_nullIncidents_returnsEmptyResult() {
        SectionResult result = section.build(null);

        assertEquals("", result.html());
        assertEquals("", result.plainText());
        assertEquals("", result.unknownHtml());
        assertTrue(result.isEmpty());
    }

    @Test
    void build_emptyIncidents_returnsEmptyResult() {
        SectionResult result = section.build(List.of());

        assertTrue(result.isEmpty());
        assertEquals("", result.html());
        assertEquals("", result.plainText());
    }

    // =========================================================================================
    // Базовий рендеринг одного закритого інциденту
    // =========================================================================================

    @Test
    void build_singleClosedIncident_rendersFormattedDatesAndDuration() {
        Instant end = T0.plusSeconds(3600);
        TrapIncident inc = closedPdc("pdc-r1-1", T0, end, "Зникнення мережевого живлення.");

        SectionResult result = section.build(List.of(inc));

        String startStr = DateUtils.formatUa(T0);
        String endStr = DateUtils.formatUa(end);
        String durStr = DurationFormat.between(T0, end);

        assertTrue(result.html().contains(startStr));
        assertTrue(result.html().contains(endStr));
        assertTrue(result.html().contains(durStr));
        assertTrue(result.html().contains("pdc-r1-1"));
        assertTrue(result.html().contains("10.0.0.1"));
        assertTrue(result.html().contains("1."));
        assertFalse(result.isEmpty());
    }

    @Test
    void build_unclosedIncident_htmlShowsEmDash_plainTextShowsNezakryto() {
        TrapIncident inc = incident(TrapEvent.CLASS_ADC, "adc-r1-1", "10.0.0.2", Severity.WARNING,
                T0, null, "Несправність вентилятора. До кінця зміни не відновлено.", List.of());

        SectionResult result = section.build(List.of(inc));

        assertTrue(result.html().contains("<td class=\"nw\">—</td>"));
        assertTrue(result.plainText().contains("незакрито"));
    }

    // =========================================================================================
    // HTML-екранування
    // =========================================================================================

    @Test
    void build_descriptionWithScriptTag_isEscapedInHtml() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(60), "<script>alert(1)</script>");

        SectionResult result = section.build(List.of(inc));

        assertFalse(result.html().contains("<script>alert(1)</script>"));
        assertTrue(result.html().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    @Test
    void build_hostnameAndIpWithSpecialChars_areEscapedInHtml() {
        TrapIncident raw = incident(TrapEvent.CLASS_PDC, "pdc-r1-1<script>", "1.2.3.4\"><b>",
                Severity.ALARM, T0, T0.plusSeconds(1), "opis", List.of());

        SectionResult result = section.build(List.of(raw));

        assertFalse(result.html().contains("<script>"));
        assertFalse(result.html().contains("\"><b>"));
        assertTrue(result.html().contains("&lt;script&gt;"));
        assertTrue(result.html().contains("&quot;&gt;&lt;b&gt;"));
    }

    // =========================================================================================
    // Нумерація рядків — своя в кожній таблиці пристрою
    // =========================================================================================

    @Test
    void build_rowNumbering_resetsPerDeviceTable() {
        TrapIncident dev1a = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "перша подія pdc-r1-1");
        TrapIncident dev1b = closedPdc("pdc-r1-1", T0.plusSeconds(20), T0.plusSeconds(30), "друга подія pdc-r1-1");
        TrapIncident dev2a = closedPdc("pdc-r2-1", T0, T0.plusSeconds(10), "перша подія pdc-r2-1");

        SectionResult result = section.build(List.of(dev1a, dev1b, dev2a));

        // Кожна таблиця має власний рахунок: pdc-r1-1 -> 1., 2.; pdc-r2-1 -> 1. (не 3.)
        String html = result.html();
        int firstTableStart = html.indexOf("pdc-r1-1");
        int secondTableStart = html.indexOf("pdc-r2-1");
        String firstTableHtml = html.substring(firstTableStart, secondTableStart);
        assertTrue(firstTableHtml.contains(">1.<"));
        assertTrue(firstTableHtml.contains(">2.<"));

        String secondTableHtml = html.substring(secondTableStart);
        assertTrue(secondTableHtml.contains(">1.<"));
        assertFalse(secondTableHtml.contains(">3.<"));
    }

    // =========================================================================================
    // Порядок пристроїв: ADC (алфавітно) перед PDC (алфавітно)
    // =========================================================================================

    @Test
    void build_deviceOrder_adcBeforePdc_thenAlphabeticalByHostname() {
        TrapIncident pdcB = closedPdc("pdc-b", T0, T0.plusSeconds(10), "d1");
        TrapIncident pdcA = closedPdc("pdc-a", T0, T0.plusSeconds(10), "d2");
        TrapIncident adcB = incident(TrapEvent.CLASS_ADC, "adc-b", "10.0.0.2", Severity.WARNING,
                T0, T0.plusSeconds(10), "d3", List.of());
        TrapIncident adcA = incident(TrapEvent.CLASS_ADC, "adc-a", "10.0.0.2", Severity.WARNING,
                T0, T0.plusSeconds(10), "d4", List.of());

        SectionResult result = section.build(List.of(pdcB, pdcA, adcB, adcA));

        String html = result.html();
        int posAdcA = html.indexOf("adc-a");
        int posAdcB = html.indexOf("adc-b");
        int posPdcA = html.indexOf("pdc-a");
        int posPdcB = html.indexOf("pdc-b");

        assertTrue(posAdcA < posAdcB);
        assertTrue(posAdcB < posPdcA);
        assertTrue(posPdcA < posPdcB);
    }

    // =========================================================================================
    // details() — вторинні трапи
    // =========================================================================================

    @Test
    void build_incidentWithDetails_renderedInHtmlAndPlainText() {
        TrapIncident inc = incident(TrapEvent.CLASS_PDC, "pdc-r1-1", "10.0.0.1", Severity.ALARM,
                T0, T0.plusSeconds(60), "Зникнення мережевого живлення. ДБЖ живив навантаження від батарей.",
                List.of("Байпас недоступний.", "Залишковий заряд батареї досяг або нижче налаштованого порогу."));

        SectionResult result = section.build(List.of(inc));

        assertTrue(result.html().contains(
                "<br><small>Байпас недоступний.; Залишковий заряд батареї досяг або нижче налаштованого порогу.</small>"));
        assertTrue(result.plainText().contains(
                "[Байпас недоступний.; Залишковий заряд батареї досяг або нижче налаштованого порогу.]"));
    }

    @Test
    void build_incidentWithoutDetails_noDetailsMarkupEmitted() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "просто опис");

        SectionResult result = section.build(List.of(inc));

        assertFalse(result.html().contains("<br><small>"));
        // Заголовок пристрою в plain-text сам має форму "[hostname / ip]" — тож перевіряємо
        // не саму наявність "[", а відсутність суфіксу деталей одразу після опису події.
        assertFalse(result.plainText().contains("просто опис ["));
    }

    // =========================================================================================
    // Severity INFO -> курсив в HTML; інші рівні -> без курсиву
    // =========================================================================================

    @Test
    void build_infoSeverity_wrappedInItalicInHtml() {
        TrapIncident inc = incident(TrapEvent.CLASS_ADC, "adc-r1-1", "10.0.0.2", Severity.INFO,
                T0, T0, "Перезапуск картки моніторингу.", List.of());

        SectionResult result = section.build(List.of(inc));

        assertTrue(result.html().contains("<i>Перезапуск картки моніторингу.</i>"));
    }

    @Test
    void build_alarmSeverity_notWrappedInItalicInHtml() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "Зникнення мережевого живлення.");

        SectionResult result = section.build(List.of(inc));

        assertFalse(result.html().contains("<i>Зникнення мережевого живлення.</i>"));
        assertTrue(result.html().contains("Зникнення мережевого живлення."));
    }

    // =========================================================================================
    // plain text: маркери ізоляції + всі рівні severity потрапляють у текст (без фільтрації)
    // =========================================================================================

    @Test
    void build_plainText_hasIsolationMarkers() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "опис");

        SectionResult result = section.build(List.of(inc));

        assertTrue(result.plainText().startsWith(
                "=== ПОДІЇ ОБЛАДНАННЯ ДАТАЦЕНТРУ (ТІЛЬКИ ЦЯ ЗМІНА — НЕ ПЕРЕНОСИТИ В НАСТУПНІ) ===\n"));
        assertTrue(result.plainText().trim().endsWith("=== КІНЕЦЬ ПОДІЙ ОБЛАДНАННЯ ДАТАЦЕНТРУ ==="));
    }

    @Test
    void build_plainText_includesInfoSeverityIncidents_noFiltering() {
        // EmersonTrapSection не фільтрує за severity — усі інциденти (включно з INFO,
        // напр. Cold Start) потрапляють як у HTML, так і у plain-text для Claude.
        TrapIncident info = incident(TrapEvent.CLASS_ADC, "adc-r1-1", "10.0.0.2", Severity.INFO,
                T0, T0, "Перезапуск картки моніторингу.", List.of());

        SectionResult result = section.build(List.of(info));

        assertTrue(result.plainText().contains("Перезапуск картки моніторингу."));
    }

    // =========================================================================================
    // PS-секція (unknownTraps) — окремо від основних інцидентів
    // =========================================================================================

    @Test
    void build_withUnknownTraps_producesUnknownHtmlSection() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "опис");
        TrapEvent unknown = new TrapEvent(T0.plusSeconds(5), "10.0.0.9", "pdc-r3-1",
                "Active:Alarm:Weird Sensor Fault", TrapEvent.CLASS_PDC);

        SectionResult result = section.build(List.of(inc), List.of(unknown));

        assertTrue(result.unknownHtml().contains("ps: нерозпізнані типи подій"));
        assertTrue(result.unknownHtml().contains("pdc-r3-1"));
        assertTrue(result.unknownHtml().contains("Active:Alarm:Weird Sensor Fault"));
        assertTrue(result.unknownHtml().contains(DateUtils.formatUa(unknown.timestamp())));
        // Основна HTML-секція лишається без PS-даних.
        assertFalse(result.html().contains("Weird Sensor Fault"));
    }

    @Test
    void build_withoutUnknownTraps_unknownHtmlIsEmpty() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "опис");

        SectionResult result = section.build(List.of(inc), List.of());

        assertEquals("", result.unknownHtml());
    }

    @Test
    void build_withNullUnknownTraps_unknownHtmlIsEmpty() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "опис");

        SectionResult result = section.build(List.of(inc), null);

        assertEquals("", result.unknownHtml());
    }

    @Test
    void build_singleArgOverload_neverPopulatesUnknownHtml() {
        TrapIncident inc = closedPdc("pdc-r1-1", T0, T0.plusSeconds(10), "опис");

        SectionResult result = section.build(List.of(inc));

        assertEquals("", result.unknownHtml());
    }

    @Test
    void build_unknownTraps_groupedByHostname_preservingFirstSeenOrder() {
        TrapEvent u1 = new TrapEvent(T0, "10.0.0.9", "pdc-b", "Active:Alarm:Foo", TrapEvent.CLASS_PDC);
        TrapEvent u2 = new TrapEvent(T0.plusSeconds(1), "10.0.0.8", "pdc-a", "Active:Alarm:Bar", TrapEvent.CLASS_PDC);
        TrapEvent u3 = new TrapEvent(T0.plusSeconds(2), "10.0.0.9", "pdc-b", "Active:Alarm:Baz", TrapEvent.CLASS_PDC);

        SectionResult result = section.build(List.of(), List.of(u1, u2, u3));

        String html = result.unknownHtml();
        int posB = html.indexOf("pdc-b");
        int posA = html.indexOf("pdc-a");
        // pdc-b з'явився першим у вхідному списку -> його блок йде першим, попри алфавіт.
        assertTrue(posB < posA);
        assertTrue(html.contains("Active:Alarm:Foo"));
        assertTrue(html.contains("Active:Alarm:Baz"));
    }

    @Test
    void build_unknownTraps_escapesTrapTypeAndHostname() {
        TrapEvent unknown = new TrapEvent(T0, "10.0.0.9", "pdc-<script>",
                "Active:Alarm:<script>alert(1)</script>", TrapEvent.CLASS_PDC);

        SectionResult result = section.build(List.of(), List.of(unknown));

        assertFalse(result.unknownHtml().contains("<script>alert(1)</script>"));
        assertTrue(result.unknownHtml().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    // =========================================================================================
    // SectionResult.isEmpty()
    // =========================================================================================

    @Test
    void sectionResult_isEmpty_trueForBlankHtml() {
        assertTrue(new SectionResult("", "text", "unknown").isEmpty());
        assertTrue(new SectionResult("   ", "text", "unknown").isEmpty());
    }

    @Test
    void sectionResult_isEmpty_falseForNonBlankHtml() {
        assertFalse(new SectionResult("<div></div>", "", "").isEmpty());
    }
}
