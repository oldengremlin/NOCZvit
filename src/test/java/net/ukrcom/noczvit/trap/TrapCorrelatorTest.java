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
import net.ukrcom.noczvit.trap.TrapCorrelator.CorrelationResult;
import net.ukrcom.noczvit.trap.TrapIncident.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrapCorrelatorTest {

    private static final Instant T0 = Instant.parse("2026-08-07T10:00:00Z");
    private static final int LINK_MINUTES = 5;

    private static TrapEvent ev(String hostname, String ip, String trapType, Instant ts, String deviceClass) {
        return new TrapEvent(ts, ip, hostname, trapType, deviceClass);
    }

    private static TrapEvent pdc(String hostname, String trapType, Instant ts) {
        return ev(hostname, "10.0.0.1", trapType, ts, TrapEvent.CLASS_PDC);
    }

    private static TrapEvent adc(String hostname, String trapType, Instant ts) {
        return ev(hostname, "10.0.0.2", trapType, ts, TrapEvent.CLASS_ADC);
    }

    private static TrapCorrelator correlator() {
        return new TrapCorrelator(LINK_MINUTES);
    }

    // =========================================================================================
    // Проста послідовність Active/Cleared на одному пристрої
    // =========================================================================================

    @Test
    void simpleActiveCleared_pdc_producesOneClosedIncident() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:High Temperature", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:High Temperature", T0.plusSeconds(600)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertTrue(inc.isClosed());
        assertEquals(T0, inc.activatedAt());
        assertEquals(T0.plusSeconds(600), inc.clearedAt());
        assertEquals(Severity.WARNING, inc.severity());
        assertEquals("Температура перевищила верхній поріг.", inc.description());
        assertTrue(result.unknownTraps().isEmpty());
    }

    @Test
    void unclosedIncident_noMatchingClearedInInput_staysOpenWithSuffix() {
        List<TrapEvent> events = List.of(adc("adc-r1-1", "Active:Alarm:Fan Fault", T0));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertFalse(inc.isClosed());
        assertNull(inc.clearedAt());
        assertEquals("Несправність вентилятора. До кінця зміни не відновлено.", inc.description());
    }

    // =========================================================================================
    // PDC — ланцюжок відключення живлення (power outage chain)
    // =========================================================================================

    @Test
    void pdcOutageChain_withBatteryAndBypassSecondaries_closedByLossOfMainsCleared() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Active:Alarm:Battery Discharging", T0.plusSeconds(10)),
                pdc("pdc-r1-1", "Active:Alarm:Bypass Not Available", T0.plusSeconds(20)),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", T0.plusSeconds(60)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertTrue(inc.isClosed());
        assertEquals(T0, inc.activatedAt());
        assertEquals(T0.plusSeconds(60), inc.clearedAt());
        assertEquals(Severity.ALARM, inc.severity());
        // "ДБЖ живив..." додається бо серед вторинних є Battery Discharging.
        assertEquals("Зникнення мережевого живлення. ДБЖ живив навантаження від батарей.", inc.description());
        // Battery Discharging/MMS On Battery уже враховані в основному описі і пропускаються
        // при побудові details — лишається лише Bypass Not Available.
        assertEquals(List.of("Байпас недоступний."), inc.details());
    }

    @Test
    void pdcOutageChain_unclosedAtEndOfShift_getsUnresolvedSuffix() {
        List<TrapEvent> events = List.of(pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertFalse(inc.isClosed());
        assertEquals("Зникнення мережевого живлення. До кінця зміни не відновлено.", inc.description());
    }

    @Test
    void pdcOutageChain_closedBySystemReturnToNormal_alsoClosesUnrelatedOpenStandalone() {
        // System Return to Normal закриває і відкритий ланцюжок живлення, і будь-які інші
        // відкриті самостійні аварії на тому ж пристрої — двома окремими інцидентами.
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Active:Alarm:High Temperature", T0.plusSeconds(5)),
                pdc("pdc-r1-1", "System Return to Normal", T0.plusSeconds(90)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(2, result.incidents().size());
        TrapIncident outage = result.incidents().stream()
                .filter(i -> i.description().startsWith("Зникнення")).findFirst().orElseThrow();
        TrapIncident temp = result.incidents().stream()
                .filter(i -> i.description().startsWith("Температура")).findFirst().orElseThrow();

        assertEquals(T0.plusSeconds(90), outage.clearedAt());
        // Без секундарних battery-трапів — суфікс "ДБЖ живив..." не додається.
        assertEquals("Зникнення мережевого живлення.", outage.description());
        assertEquals(T0.plusSeconds(90), temp.clearedAt());
    }

    @Test
    void pdcOutageChain_altRootTrap_systemInputPowerProblem_alsoOpensChain() {
        // r3/r4 прошивка надсилає "System Input Power Problem" замість "Loss of Mains" —
        // обидва входять до CHAIN_ROOT_ACTIVE і відкривають той самий ланцюжок.
        List<TrapEvent> events = List.of(
                pdc("pdc-r3-1", "Active:Alarm:System Input Power Problem", T0),
                pdc("pdc-r3-1", "Cleared:Alarm:System Input Power Problem", T0.plusSeconds(30)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        assertEquals("Проблема з вхідним живленням.", result.incidents().get(0).description());
        assertTrue(result.incidents().get(0).isClosed());
    }

    // =========================================================================================
    // Cold Start linking — прив'язка до відновлення живлення PDC
    // =========================================================================================

    @Test
    void coldStart_withinLinkWindowAfterPdcRestoration_isAnnotatedAsLinked() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", T0.plusSeconds(60)), // restoration = T0+60
                adc("adc-r1-9", "Cold Start", T0.plusSeconds(60 + 120))); // +2 min, well within 5-min window

        CorrelationResult result = correlator().correlate(events);

        TrapIncident coldStart = result.incidents().stream()
                .filter(i -> i.hostname().equals("adc-r1-9")).findFirst().orElseThrow();
        assertEquals(Severity.INFO, coldStart.severity());
        assertEquals(coldStart.activatedAt(), coldStart.clearedAt());
        assertEquals("Перезапуск картки моніторингу. Пов'язано з відновленням мережевого живлення.",
                coldStart.description());
    }

    @Test
    void coldStart_outsideLinkWindow_isNotAnnotated() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", T0.plusSeconds(60)), // restoration = T0+60
                adc("adc-r1-9", "Cold Start", T0.plusSeconds(60 + 6 * 60))); // +6 min, past 5-min window

        CorrelationResult result = correlator().correlate(events);

        TrapIncident coldStart = result.incidents().stream()
                .filter(i -> i.hostname().equals("adc-r1-9")).findFirst().orElseThrow();
        assertEquals("Перезапуск картки моніторингу.", coldStart.description());
    }

    @Test
    void coldStart_boundary_exactlyAtLinkWindowEnd_isNotLinked() {
        Instant restoration = T0.plusSeconds(60);
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", restoration),
                // Рівно 5 хв (300с) після відновлення: isBefore(restoration+300) хибне на межі -> НЕ пов'язано.
                adc("adc-r1-9", "Cold Start", restoration.plusSeconds(LINK_MINUTES * 60)));

        CorrelationResult result = correlator().correlate(events);

        TrapIncident coldStart = result.incidents().stream()
                .filter(i -> i.hostname().equals("adc-r1-9")).findFirst().orElseThrow();
        assertEquals("Перезапуск картки моніторингу.", coldStart.description());
    }

    @Test
    void coldStart_boundary_oneSecondBeforeLinkWindowEnd_isLinked() {
        Instant restoration = T0.plusSeconds(60);
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", restoration),
                adc("adc-r1-9", "Cold Start", restoration.plusSeconds(LINK_MINUTES * 60 - 1)));

        CorrelationResult result = correlator().correlate(events);

        TrapIncident coldStart = result.incidents().stream()
                .filter(i -> i.hostname().equals("adc-r1-9")).findFirst().orElseThrow();
        assertTrue(coldStart.description().endsWith("Пов'язано з відновленням мережевого живлення."));
    }

    @Test
    void coldStart_atExactRestorationInstant_isLinked() {
        Instant restoration = T0.plusSeconds(60);
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", restoration),
                adc("adc-r1-9", "Cold Start", restoration));

        CorrelationResult result = correlator().correlate(events);

        TrapIncident coldStart = result.incidents().stream()
                .filter(i -> i.hostname().equals("adc-r1-9")).findFirst().orElseThrow();
        assertTrue(coldStart.description().endsWith("Пов'язано з відновленням мережевого живлення."));
    }

    @Test
    void coldStart_beforeRestoration_isNotLinked() {
        Instant restoration = T0.plusSeconds(60);
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", restoration),
                adc("adc-r1-9", "Cold Start", restoration.minusSeconds(10)));

        CorrelationResult result = correlator().correlate(events);

        TrapIncident coldStart = result.incidents().stream()
                .filter(i -> i.hostname().equals("adc-r1-9")).findFirst().orElseThrow();
        assertEquals("Перезапуск картки моніторингу.", coldStart.description());
    }

    @Test
    void coldStart_linking_isScopedByRoom_notByAnyPdcRestoration() {
        // r1 відновлюється задовго до Cold Start (поза вікном), r2 — щойно (у вікні).
        // ADC у r2 має прив'язатись лише через відновлення r2, а не через "будь-яке" PDC.
        Instant r1Restoration = T0;
        Instant r2Restoration = T0.plusSeconds(500);
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0.minusSeconds(30)),
                pdc("pdc-r1-1", "Cleared:Alarm:Loss of Mains", r1Restoration),
                pdc("pdc-r2-1", "Active:Alarm:Loss of Mains", T0.plusSeconds(400)),
                pdc("pdc-r2-1", "Cleared:Alarm:Loss of Mains", r2Restoration),
                adc("adc-r2-5", "Cold Start", r2Restoration.plusSeconds(60)));

        CorrelationResult result = correlator().correlate(events);

        TrapIncident coldStart = result.incidents().stream()
                .filter(i -> i.hostname().equals("adc-r2-5")).findFirst().orElseThrow();
        assertTrue(coldStart.description().endsWith("Пов'язано з відновленням мережевого живлення."));
    }

    // =========================================================================================
    // Кілька пристроїв одночасно — стани не плутаються
    // =========================================================================================

    @Test
    void multipleHostnames_stateIsolatedPerDevice() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                pdc("pdc-r1-1", "Active:Alarm:Battery Discharging", T0.plusSeconds(5)),
                pdc("pdc-r2-1", "Active:Alarm:Loss of Mains", T0));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(2, result.incidents().size());
        TrapIncident r1 = result.incidents().stream().filter(i -> i.hostname().equals("pdc-r1-1")).findFirst().orElseThrow();
        TrapIncident r2 = result.incidents().stream().filter(i -> i.hostname().equals("pdc-r2-1")).findFirst().orElseThrow();

        // pdc-r1-1 мало вторинний Battery Discharging -> суфікс "ДБЖ живив..." присутній.
        assertTrue(r1.description().contains("ДБЖ живив навантаження від батарей."));
        // pdc-r2-1 не отримував жодних вторинних трапів -> суфікса немає, витік стану відсутній.
        assertFalse(r2.description().contains("ДБЖ живив навантаження від батарей."));
    }

    // =========================================================================================
    // IGNORE_TRAPS — штатні переходи Unit On / Unit On Standby
    // =========================================================================================

    @Test
    void ignoredTraps_unitOnAndStandbyVariants_produceNoIncidents() {
        List<TrapEvent> events = List.of(
                adc("adc-r1-1", "Active:Alarm:Unit On", T0),
                adc("adc-r1-1", "Cleared:Alarm:Unit On", T0.plusSeconds(1)),
                adc("adc-r1-1", "Active:Alarm:Unit On Standby", T0.plusSeconds(2)),
                adc("adc-r1-1", "Cleared:Alarm:Unit On Standby", T0.plusSeconds(3)),
                adc("adc-r1-1", "Active:Alarm:Unit Standby", T0.plusSeconds(4)),
                adc("adc-r1-1", "Cleared:Alarm:Unit Standby", T0.plusSeconds(5)));

        CorrelationResult result = correlator().correlate(events);

        assertTrue(result.incidents().isEmpty());
        assertTrue(result.unknownTraps().isEmpty());
    }

    // =========================================================================================
    // SELF_CLOSING_ACTIVE — Compressor Short Cycle: clearedAt = activatedAt, подальший Cleared ігнорується
    // =========================================================================================

    @Test
    void selfClosingActive_compressorShortCycle_closesImmediately_andIgnoresLaterCleared() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Compressor Short Cycle", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Compressor Short Cycle", T0.plusSeconds(10)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertEquals(inc.activatedAt(), inc.clearedAt());
        assertEquals(Severity.WARNING, inc.severity());
        assertFalse(inc.description().contains("До кінця зміни не відновлено"));
    }

    // =========================================================================================
    // Active:Alarm:Battery Discharging → MMS On Battery merge (спільний процес)
    // =========================================================================================

    @Test
    void batteryDischarging_followedByMmsOnBattery_mergesIntoSingleClosedIncident() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Battery Discharging", T0),
                pdc("pdc-r1-1", "Active:Alarm:MMS On Battery", T0.plusSeconds(30)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertEquals(T0, inc.activatedAt());
        assertEquals(T0.plusSeconds(30), inc.clearedAt());
        assertEquals(Severity.ALARM, inc.severity());
        assertEquals("ДБЖ перейшов на живлення від батарей.", inc.description());
    }

    @Test
    void batteryDischarging_closedByOrdinaryCleared_usesClosedDescriptionVariant() {
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Battery Discharging", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Battery Discharging", T0.plusSeconds(30)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        assertEquals("ДБЖ перейшов на живлення від батарей.", result.incidents().get(0).description());
    }

    @Test
    void batteryDischarging_unclosed_isInNoUnresolvedSuffixSet_soNoSuffixAppended() {
        List<TrapEvent> events = List.of(pdc("pdc-r1-1", "Active:Alarm:Battery Discharging", T0));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertFalse(inc.isClosed());
        // "Battery Discharging" входить у NO_UNRESOLVED_SUFFIX -> суфікс "До кінця..." не додається,
        // навіть попри те що подія лишилась незакритою.
        assertEquals("Батарея розряджається.", inc.description());
    }

    // =========================================================================================
    // Невідомі/нерозпізнані трапи
    // =========================================================================================

    @Test
    void unknownActiveTrap_closed_goesToUnknownTrapsAndAlsoProducesAFallbackIncident() {
        // ФІКСАЦІЯ ПОВЕДІНКИ: невідомий Active:Alarm:X не лише потрапляє в unknownTraps —
        // він одночасно ставиться в openStandalones і, отже, все одно породжує TrapIncident
        // (з fallback-описом = сама назва трапу без префікса). Див. TrapCorrelator.java:439-444.
        List<TrapEvent> events = List.of(
                pdc("pdc-r1-1", "Active:Alarm:Weird Sensor Fault", T0),
                pdc("pdc-r1-1", "Cleared:Alarm:Weird Sensor Fault", T0.plusSeconds(20)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.unknownTraps().size());
        assertEquals("Active:Alarm:Weird Sensor Fault", result.unknownTraps().get(0).trapType());

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertTrue(inc.isClosed());
        assertEquals("Weird Sensor Fault", inc.description());
        assertEquals(Severity.WARNING, inc.severity()); // TRAP_SEVERITY default для невідомого типу
    }

    @Test
    void unknownActiveTrap_unclosed_alsoGetsUnresolvedSuffix() {
        List<TrapEvent> events = List.of(pdc("pdc-r1-1", "Active:Alarm:Weird Sensor Fault", T0));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.unknownTraps().size());
        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertFalse(inc.isClosed());
        assertEquals("Weird Sensor Fault До кінця зміни не відновлено.", inc.description());
    }

    @Test
    void trulyUnrecognizedTrapFormat_producesNoIncidentAndIsNotInUnknownTraps() {
        // ФІКСАЦІЯ ПОВЕДІНКИ: рядок, що не має ані "Active:Alarm:" ані "Cleared:Alarm:" префіксу,
        // не потрапляє в жоден catch-all — падає у фінальний log.debug("truly unhandled") і
        // зникає безслідно: не інцидент, не unknownTraps. Див. TrapCorrelator.java:456.
        List<TrapEvent> events = List.of(pdc("pdc-r1-1", "Something Completely Different", T0));

        CorrelationResult result = correlator().correlate(events);

        assertTrue(result.incidents().isEmpty());
        assertTrue(result.unknownTraps().isEmpty());
    }

    @Test
    void orphanClearedTrap_withoutMatchingActive_producesNothing() {
        List<TrapEvent> events = List.of(pdc("pdc-r1-1", "Cleared:Alarm:High Temperature", T0));

        CorrelationResult result = correlator().correlate(events);

        assertTrue(result.incidents().isEmpty());
        assertTrue(result.unknownTraps().isEmpty());
    }

    // =========================================================================================
    // Monitoring Card Reboot — точкова подія, самозакрита (clearedAt = activatedAt), як і Cold Start
    // =========================================================================================

    @Test
    void monitoringCardReboot_selfClosing_sameAsColdStart() {
        // Той самий текст опису, що й у Cold Start ("Перезапуск картки моніторингу."), і та
        // сама семантика: подія почалась і закінчилась одномоментно, тож clearedAt = activatedAt,
        // а не null — інакше звіт показував "—"/"незакрито" для того, що вже відбулось.
        List<TrapEvent> events = List.of(pdc("pdc-r1-1", "Monitoring Card Reboot", T0));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(1, result.incidents().size());
        TrapIncident inc = result.incidents().get(0);
        assertTrue(inc.isClosed());
        assertEquals(T0, inc.clearedAt());
        assertEquals("Перезапуск картки моніторингу.", inc.description());
        assertEquals(Severity.INFO, inc.severity());
    }

    // =========================================================================================
    // ADC — System Return to Normal закриває всі відкриті події
    // =========================================================================================

    @Test
    void adc_systemReturnToNormal_closesAllOpenStandalones() {
        List<TrapEvent> events = List.of(
                adc("adc-r1-1", "Active:Alarm:High Temperature", T0),
                adc("adc-r1-1", "Active:Alarm:Low Humidity", T0.plusSeconds(5)),
                adc("adc-r1-1", "System Return to Normal", T0.plusSeconds(120)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(2, result.incidents().size());
        assertTrue(result.incidents().stream().allMatch(i -> i.clearedAt().equals(T0.plusSeconds(120))));
    }

    // =========================================================================================
    // Сортування підсумкового списку інцидентів за activatedAt
    // =========================================================================================

    @Test
    void correlate_resultIsSortedByActivatedAt_acrossDevicesAndClasses() {
        List<TrapEvent> events = List.of(
                adc("adc-r2-1", "Active:Alarm:High Temperature", T0.plusSeconds(300)),
                pdc("pdc-r1-1", "Active:Alarm:Low Battery", T0),
                adc("adc-r1-1", "Active:Alarm:Fan Fault", T0.plusSeconds(100)));

        CorrelationResult result = correlator().correlate(events);

        assertEquals(3, result.incidents().size());
        assertEquals(T0, result.incidents().get(0).activatedAt());
        assertEquals(T0.plusSeconds(100), result.incidents().get(1).activatedAt());
        assertEquals(T0.plusSeconds(300), result.incidents().get(2).activatedAt());
    }

    @Test
    void correlate_emptyInput_returnsEmptyNonNullResult() {
        CorrelationResult result = correlator().correlate(List.of());

        assertTrue(result.incidents().isEmpty());
        assertTrue(result.unknownTraps().isEmpty());
    }

    // =========================================================================================
    // normalizeCategory — нормалізація прошивки Room4 (Message:/Warning: -> Alarm:)
    // =========================================================================================

    @Test
    void normalizeCategory_activeMessage_toActiveAlarm() {
        assertEquals("Active:Alarm:Foo", TrapCorrelator.normalizeCategory("Active:Message:Foo"));
    }

    @Test
    void normalizeCategory_activeWarning_toActiveAlarm() {
        assertEquals("Active:Alarm:Foo", TrapCorrelator.normalizeCategory("Active:Warning:Foo"));
    }

    @Test
    void normalizeCategory_clearedMessage_toClearedAlarm() {
        assertEquals("Cleared:Alarm:Foo", TrapCorrelator.normalizeCategory("Cleared:Message:Foo"));
    }

    @Test
    void normalizeCategory_clearedWarning_toClearedAlarm() {
        assertEquals("Cleared:Alarm:Foo", TrapCorrelator.normalizeCategory("Cleared:Warning:Foo"));
    }

    @Test
    void normalizeCategory_messageSystemReturnToNormal_stripsMessagePrefix() {
        assertEquals("System Return to Normal", TrapCorrelator.normalizeCategory("Message:System Return to Normal"));
    }

    @Test
    void normalizeCategory_alreadyCanonicalAlarm_isUnchanged() {
        assertEquals("Active:Alarm:Loss of Mains", TrapCorrelator.normalizeCategory("Active:Alarm:Loss of Mains"));
    }

    @Test
    void normalizeCategory_embeddedColonInPayload_splitsOnFirstColonAfterCategory() {
        assertEquals("Active:Alarm:Something:Extra",
                TrapCorrelator.normalizeCategory("Active:Message:Something:Extra"));
    }

    // =========================================================================================
    // extractRoom — витяг ідентифікатора кімнати з hostname
    // =========================================================================================

    @Test
    void extractRoom_standardHostname() {
        assertEquals("r1", TrapCorrelator.extractRoom("adc-r1-1"));
    }

    @Test
    void extractRoom_multiDigitRoom() {
        assertEquals("r10", TrapCorrelator.extractRoom("pdc-r10-2"));
    }

    @Test
    void extractRoom_noDash_returnsEmptyString() {
        assertEquals("", TrapCorrelator.extractRoom("adc"));
    }

    @Test
    void extractRoom_exactlyTwoParts() {
        assertEquals("r1", TrapCorrelator.extractRoom("adc-r1"));
    }
}
