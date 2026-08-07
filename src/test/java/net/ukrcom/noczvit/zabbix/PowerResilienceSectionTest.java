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
package net.ukrcom.noczvit.zabbix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.ukrcom.noczvit.zabbix.PowerResilienceResult.InterfaceObservation;
import net.ukrcom.noczvit.zabbix.PowerResilienceSection.SectionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerResilienceSectionTest {

    /** Fluent білдер лише для тестів — {@link PowerResilienceResult} має 18 полів record'у. */
    private static class ResultBuilder {

        String host = "host1";
        String location = "host1";
        Instant fallInstant = Instant.ofEpochSecond(1_700_000_000L);
        Instant recoveryInstant = Instant.ofEpochSecond(1_700_000_600L);
        int alreadyDownAtFall = 0;
        int stillUpAtFall = 0;
        int recoveredBeforeUs = 0;
        int stillDownAfterUs = 0;
        int noDataAtFall = 0;
        int noDataAtRecovery = 0;
        int ignoredPorts = 0;
        List<InterfaceObservation> alreadyDownNames = new ArrayList<>();
        List<InterfaceObservation> recoveredNames = new ArrayList<>();
        List<InterfaceObservation> stillDownNames = new ArrayList<>();
        Optional<Long> uptimeBefore = Optional.empty();
        Optional<Long> uptimeAfter = Optional.empty();
        Optional<Instant> restartDetectedAt = Optional.empty();
        String verdict = "";

        ResultBuilder host(String v) {
            host = v;
            return this;
        }

        ResultBuilder location(String v) {
            location = v;
            return this;
        }

        ResultBuilder fallInstant(Instant v) {
            fallInstant = v;
            return this;
        }

        ResultBuilder recoveryInstant(Instant v) {
            recoveryInstant = v;
            return this;
        }

        ResultBuilder alreadyDown(int n, List<InterfaceObservation> names) {
            alreadyDownAtFall = n;
            alreadyDownNames = names;
            return this;
        }

        ResultBuilder stillUp(int total, int recovered, int stillDown, int noDataRecovery) {
            stillUpAtFall = total;
            recoveredBeforeUs = recovered;
            stillDownAfterUs = stillDown;
            noDataAtRecovery = noDataRecovery;
            return this;
        }

        ResultBuilder recoveredNames(List<InterfaceObservation> names) {
            recoveredNames = names;
            return this;
        }

        ResultBuilder stillDownNames(List<InterfaceObservation> names) {
            stillDownNames = names;
            return this;
        }

        ResultBuilder noDataAtFall(int v) {
            noDataAtFall = v;
            return this;
        }

        ResultBuilder ignoredPorts(int v) {
            ignoredPorts = v;
            return this;
        }

        ResultBuilder uptime(long before, long after) {
            uptimeBefore = Optional.of(before);
            uptimeAfter = Optional.of(after);
            return this;
        }

        ResultBuilder restartDetectedAt(Instant v) {
            restartDetectedAt = Optional.of(v);
            return this;
        }

        ResultBuilder verdict(String v) {
            verdict = v;
            return this;
        }

        PowerResilienceResult build() {
            return new PowerResilienceResult(host, location, fallInstant, recoveryInstant,
                    alreadyDownAtFall, stillUpAtFall, recoveredBeforeUs, stillDownAfterUs,
                    noDataAtFall, noDataAtRecovery, ignoredPorts,
                    List.copyOf(alreadyDownNames), List.copyOf(recoveredNames), List.copyOf(stillDownNames),
                    uptimeBefore, uptimeAfter, restartDetectedAt, verdict);
        }
    }

    private static ResultBuilder result() {
        return new ResultBuilder();
    }

    private final PowerResilienceSection section = new PowerResilienceSection();

    // ---- Порожній список -----------------------------------------------------------------------

    @Test
    void build_emptyList_returnsEmptySectionResult() {
        SectionResult sr = section.build(List.of());

        assertTrue(sr.isEmpty());
        assertEquals("", sr.html());
        assertEquals("", sr.plainText());
    }

    // ---- Групування по location, власна нумерація на таблицю ------------------------------------

    @Test
    void build_groupsByLocation_withOwnNumberingPerLocationTable() {
        PowerResilienceResult a1 = result().host("a1").location("Локація А")
                .alreadyDown(1, List.of()).stillUp(0, 0, 0, 0).build();
        PowerResilienceResult a2 = result().host("a2").location("Локація А")
                .alreadyDown(1, List.of()).stillUp(0, 0, 0, 0).build();
        PowerResilienceResult a3 = result().host("a3").location("Локація А")
                .alreadyDown(1, List.of()).stillUp(0, 0, 0, 0).build();
        PowerResilienceResult b1 = result().host("b1").location("Локація Б")
                .alreadyDown(1, List.of()).stillUp(0, 0, 0, 0).build();
        PowerResilienceResult b2 = result().host("b2").location("Локація Б")
                .alreadyDown(1, List.of()).stillUp(0, 0, 0, 0).build();

        String html = section.build(List.of(a1, a2, a3, b1, b2)).html();

        int locAIdx = html.indexOf("Локація А");
        int locBIdx = html.indexOf("Локація Б");
        assertTrue(locAIdx >= 0 && locBIdx > locAIdx);

        String tableA = html.substring(locAIdx, locBIdx);
        String tableB = html.substring(locBIdx);

        // Нумерація перезапускається в кожній таблиці локації: 1,2,3 в А і знову 1,2 в Б.
        assertTrue(tableA.contains(">1.<") && tableA.contains(">2.<") && tableA.contains(">3.<"));
        assertTrue(tableB.contains(">1.<") && tableB.contains(">2.<"));
        assertFalse(tableB.contains(">3.<"));
    }

    // ---- Заголовки колонок -----------------------------------------------------------------------

    @Test
    void build_tableHeaders_exactTextAndNwClassOnFirstFour() {
        PowerResilienceResult r = result().alreadyDown(1, List.of()).build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("<th style=\"width:30px\">№</th>"));
        assertTrue(html.contains("<th class=\"nw\">Обладнання</th>"));
        assertTrue(html.contains("<th class=\"nw\">Початок</th>"));
        assertTrue(html.contains("<th class=\"nw\">Закінчення</th>"));
        assertTrue(html.contains("<th class=\"nw\">Тривалість</th>"));
        assertTrue(html.contains("<th>Результат аудиту</th>"));
    }

    // ---- Вердикт жирним -----------------------------------------------------------------------

    @Test
    void build_nonEmptyVerdict_renderedBold() {
        PowerResilienceResult r = result().alreadyDown(2, List.of()).stillUp(0, 0, 0, 0)
                .verdict("Усі відомі порти впали раніше за вузол — ймовірно, резервне живлення "
                        + "протримало довше за клієнтів.")
                .build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("<p><b>Усі відомі порти впали раніше за вузол"));
    }

    @Test
    void build_emptyVerdict_noBoldVerdictParagraph() {
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of()).stillUp(1, 1, 0, 0)
                .recoveredNames(List.of(new InterfaceObservation("Interface 2(x)", Instant.ofEpochSecond(1700000601L))))
                .build();

        String html = section.build(List.of(r)).html();

        assertFalse(html.contains("<p><b>"));
    }

    // ---- restartDetectedAt: завжди показується, замінює uptime-факт --------------------------

    @Test
    void build_restartDetectedAt_shownEvenWithNonEmptyVerdict_andSuppressesUptimeFact() {
        PowerResilienceResult r = result()
                .alreadyDown(2, List.of()).stillUp(0, 0, 0, 0)
                .verdict("Усі відомі порти впали раніше за вузол — ймовірно, резервне живлення "
                        + "протримало довше за клієнтів.")
                .uptime(5000L, 30L)  // uptimeDecreased() == true
                .restartDetectedAt(Instant.ofEpochSecond(1_700_000_610L))
                .build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("has been restarted"));
        // Слабший ізольований uptime-факт НЕ показується поряд із сильнішим restart-сигналом.
        assertFalse(html.contains("Zabbix зафіксував зменшення лічильника uptime"));
    }

    @Test
    void build_noRestart_emptyVerdict_uptimeDecreasedTrue_showsUptimeFact() {
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of()).stillUp(1, 1, 0, 0)
                .recoveredNames(List.of(new InterfaceObservation("Interface 2(x)", Instant.ofEpochSecond(1700000601L))))
                .uptime(5000L, 30L)
                .build();

        String html = section.build(List.of(r)).html();

        assertFalse(html.contains("has been restarted"));
        assertTrue(html.contains("Zabbix зафіксував зменшення лічильника uptime з 5000 на 30 с."));
    }

    @Test
    void build_noRestart_emptyVerdict_uptimeNotDecreased_showsNeitherFact() {
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of()).stillUp(1, 1, 0, 0)
                .recoveredNames(List.of(new InterfaceObservation("Interface 2(x)", Instant.ofEpochSecond(1700000601L))))
                .uptime(30L, 5000L)  // зростання, не uptimeDecreased
                .build();

        String html = section.build(List.of(r)).html();

        assertFalse(html.contains("has been restarted"));
        assertFalse(html.contains("Zabbix зафіксував зменшення лічильника uptime"));
    }

    @Test
    void build_restartDetectedAt_shownOnEvenAmbiguousMiddleWithoutVerdict() {
        // verdict=="" (середина), але restart є — показується попри неоднозначність.
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of()).stillUp(1, 1, 0, 0)
                .recoveredNames(List.of(new InterfaceObservation("Interface 2(x)", Instant.ofEpochSecond(1700000601L))))
                .restartDetectedAt(Instant.ofEpochSecond(1_700_000_610L))
                .build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("has been restarted"));
    }

    // ---- totalKnown()==0: дві різні причини ----------------------------------------------------

    @Test
    void build_totalKnownZero_dueToIgnoredPortsOnly_showsIgnoredPortsMessage() {
        PowerResilienceResult r = result().noDataAtFall(0).ignoredPorts(3).build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains(
                "Немає даних для аналізу — усі 3 портів хоста без опису, позначені вільними, або виключеного типу "
                        + "(налаштування)."));
        assertFalse(html.contains("жоден інтерфейс не мав історії"));
    }

    @Test
    void build_totalKnownZero_dueToMissingHistory_showsNoHistoryMessage() {
        PowerResilienceResult r = result().noDataAtFall(4).ignoredPorts(0).build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("Немає даних для аналізу — жоден інтерфейс не мав історії на момент падіння вузла."));
        assertFalse(html.contains("без опису або позначені вільними"));
    }

    // ---- noDataAtFall / ignoredPorts рядки, коли totalKnown() > 0 ------------------------------

    @Test
    void build_totalKnownPositive_showsNoDataAndIgnoredCountsSeparately() {
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of())
                .stillUp(0, 0, 0, 0)
                .noDataAtFall(2)
                .ignoredPorts(5)
                .build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("2 портів не враховано — не мали історії на момент падіння вузла."));
        assertTrue(html.contains("5 портів не враховано — без опису, позначені вільними"));
    }

    // ---- stillUpAtFall == 0: параграф "З тих, що ще працювали" не з'являється ------------------

    @Test
    void build_stillUpAtFallZero_noWorkingPortsParagraph() {
        PowerResilienceResult r = result().alreadyDown(3, List.of()).stillUp(0, 0, 0, 0).build();

        String html = section.build(List.of(r)).html();

        assertFalse(html.contains("З тих, що ще працювали"));
    }

    @Test
    void build_stillUpAtFallPositive_showsWorkingPortsParagraphWithBreakdown() {
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of())
                .stillUp(3, 1, 1, 1)
                .recoveredNames(List.of(new InterfaceObservation("p2", Instant.ofEpochSecond(1700000601L))))
                .stillDownNames(List.of(new InterfaceObservation("p3", Instant.ofEpochSecond(1700000602L))))
                .build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("З тих, що ще працювали"));
        assertTrue(html.contains("лишались недоступні й після його відновлення"));
        assertTrue(html.contains("немає знімка на момент відновлення"));
    }

    // ---- plainText: компактний, без назв портів -------------------------------------------------

    @Test
    void build_plainText_neverContainsPortNames() {
        PowerResilienceResult r = result()
                .host("core-1").location("Локація В")
                .alreadyDown(1, List.of(new InterfaceObservation("Interface 1(secret-client-a)", Instant.ofEpochSecond(1700000001L))))
                .stillUp(2, 1, 1, 0)
                .recoveredNames(List.of(new InterfaceObservation("Interface 2(secret-client-b)", Instant.ofEpochSecond(1700000601L))))
                .stillDownNames(List.of(new InterfaceObservation("Interface 3(secret-client-c)", Instant.ofEpochSecond(1700000602L))))
                .build();

        String plainText = section.build(List.of(r)).plainText();

        for (InterfaceObservation obs : List.of(
                r.alreadyDownNames().get(0), r.recoveredNames().get(0), r.stillDownNames().get(0))) {
            assertFalse(plainText.contains(obs.name()),
                    "plainText не повинен містити назву порту: " + obs.name());
        }
        assertTrue(plainText.contains("core-1") || plainText.contains("Локація В"));
    }

    @Test
    void build_plainText_totalKnownZero_reportsNoData() {
        PowerResilienceResult r = result().noDataAtFall(5).build();

        String plainText = section.build(List.of(r)).plainText();

        assertTrue(plainText.contains("даних для аналізу немає"));
    }

    @Test
    void build_plainText_includesVerdictWhenPresent_orAmbiguousNote() {
        PowerResilienceResult withVerdict = result()
                .alreadyDown(2, List.of()).stillUp(0, 0, 0, 0)
                .verdict("Жоден з відомих портів не впав раніше за вузол.")
                .build();
        PowerResilienceResult ambiguous = result()
                .alreadyDown(1, List.of()).stillUp(1, 1, 0, 0)
                .recoveredNames(List.of(new InterfaceObservation("p", Instant.ofEpochSecond(1700000601L))))
                .build();

        String pt1 = section.build(List.of(withVerdict)).plainText();
        String pt2 = section.build(List.of(ambiguous)).plainText();

        assertTrue(pt1.contains("Висновок: Жоден з відомих портів не впав раніше за вузол."));
        assertTrue(pt2.contains("Однозначного висновку немає — вирішує інженер."));
    }

    @Test
    void build_plainText_mentionsRestartWhenPresent() {
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of()).stillUp(1, 1, 0, 0)
                .recoveredNames(List.of(new InterfaceObservation("p", Instant.ofEpochSecond(1700000601L))))
                .restartDetectedAt(Instant.ofEpochSecond(1_700_000_610L))
                .build();

        String plainText = section.build(List.of(r)).plainText();

        assertTrue(plainText.contains("Zabbix підтвердив перезавантаження обладнання"));
    }

    // ---- appendNames: підписи груп у HTML --------------------------------------------------------

    @Test
    void build_appendNames_rendersLabelsWithObservedAtForEachGroup() {
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of(new InterfaceObservation("p1", Instant.ofEpochSecond(1700000001L))))
                .stillUp(2, 1, 1, 0)
                .recoveredNames(List.of(new InterfaceObservation("p2", Instant.ofEpochSecond(1700000601L))))
                .stillDownNames(List.of(new InterfaceObservation("p3", Instant.ofEpochSecond(1700000602L))))
                .build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("Впали раніше вузла"));
        assertTrue(html.contains("Активні на момент відновлення вузла"));
        assertTrue(html.contains("Лишались недоступні після відновлення вузла"));
        assertTrue(html.contains(">p1<") || html.contains("p1 —"));
        assertTrue(html.contains("p2 —"));
        assertTrue(html.contains("p3 —"));
    }

    @Test
    void build_appendNames_omitsLabelWhenGroupEmpty() {
        // stillDownNames порожній — навіть якщо stillDownAfterUs>0 логічно не буває без імен,
        // але сам appendNames() перевіряє лише порожність списку.
        PowerResilienceResult r = result()
                .alreadyDown(1, List.of(new InterfaceObservation("p1", Instant.ofEpochSecond(1700000001L))))
                .stillUp(0, 0, 0, 0)
                .build();

        String html = section.build(List.of(r)).html();

        assertTrue(html.contains("Впали раніше вузла"));
        assertFalse(html.contains("Активні на момент відновлення вузла"));
        assertFalse(html.contains("Лишались недоступні після відновлення вузла"));
    }

    // ---- Кілька результатів у одній локації, різний порядок ------------------------------------

    @Test
    void build_multipleResultsSameLocation_sortedByFallInstantWithinTable() {
        PowerResilienceResult later = result().host("later").location("Локація Г")
                .fallInstant(Instant.ofEpochSecond(2000)).recoveryInstant(Instant.ofEpochSecond(2500))
                .alreadyDown(1, List.of()).build();
        PowerResilienceResult earlier = result().host("earlier").location("Локація Г")
                .fallInstant(Instant.ofEpochSecond(1000)).recoveryInstant(Instant.ofEpochSecond(1500))
                .alreadyDown(1, List.of()).build();

        String html = section.build(List.of(later, earlier)).html();

        // Сортування всередині build(): по location, потім по fallInstant — earlier має йти першим.
        assertTrue(html.indexOf("earlier") < html.indexOf("later"));
    }
}
