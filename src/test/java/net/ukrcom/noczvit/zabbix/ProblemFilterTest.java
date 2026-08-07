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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Тести {@link ProblemFilter}: кожне правило фільтрації перевіряється окремо на парі
 * "фільтрується" / "майже ідентичний, але не фільтрується".
 */
class ProblemFilterTest {

    private static ZabbixProblem problem(String host, String name) {
        return new ZabbixProblem(host, name, 1_700_000_000L, 0L);
    }

    private static ZabbixProblem problem(String host, String name, long clock) {
        return new ZabbixProblem(host, name, clock, 0L);
    }

    private static Incident imapIncident(String device, long eventTs) {
        return new Incident("Локація", device, eventTs, eventTs, "dateStr", "dateStr",
                Source.PD, Status.START, "опис", List.of(), "in-reply-to");
    }

    // ---- Порожні вхідні списки: без винятків ----

    @Test
    @DisplayName("Порожній список problems -> повертає порожній список, без винятків")
    void filter_emptyProblems_returnsEmptyList() {
        List<ZabbixProblem> result = ProblemFilter.filter(List.of(), List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Порожній список imapIncidents -> проблеми, що проходять інші правила, залишаються")
    void filter_emptyImapIncidents_keepsNonFilteredProblems() {
        List<ZabbixProblem> problems = List.of(problem("r234-1", "Link down"));

        List<ZabbixProblem> result = ProblemFilter.filter(problems, List.of());

        assertEquals(problems, result);
    }

    // ---- Порожній host ----

    @Test
    @DisplayName("Порожній host -> відфільтровується; майже ідентична проблема з непорожнім host -> ні")
    void filter_blankHost_isFiltered() {
        ZabbixProblem blank = problem("", "Link down");
        ZabbixProblem whitespaceOnly = problem("   ", "Link down");
        ZabbixProblem kept = problem("r234-1", "Link down");

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(blank, whitespaceOnly, kept), List.of());

        assertEquals(List.of(kept), result);
    }

    // ---- Host SDH-OSM ----

    @Test
    @DisplayName("host == 'SDH-OSM' -> відфільтровується (події OSM вже йдуть через IMAP)")
    void filter_sdhOsmHost_isFiltered() {
        ZabbixProblem sdhOsm = problem("SDH-OSM", "Trap received");
        ZabbixProblem kept = problem("r234-1", "Trap received");

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(sdhOsm, kept), List.of());

        assertEquals(List.of(kept), result);
    }

    @Test
    @DisplayName("host SDH-OSM: порівняння точне (equals), тож майже ідентичний host не фільтрується цим правилом")
    void filter_sdhOsmHost_isExactEqualityNotContains() {
        // "SDH-OSM-2" не дорівнює "SDH-OSM" -> це правило його не чіпає.
        ZabbixProblem notExactMatch = problem("SDH-OSM-2", "Trap received");
        // Порівняння регістрозалежне (String.equals) -> нижній регістр теж не фільтрується цим правилом.
        ZabbixProblem differentCase = problem("sdh-osm", "Trap received");

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(notExactMatch, differentCase), List.of());

        assertEquals(List.of(notExactMatch, differentCase), result);
    }

    // ---- No SNMP data collection ----

    @Test
    @DisplayName("'No SNMP data collection' (без урахування регістру) -> відфільтровується; "
            + "майже ідентична назва без цієї фрази -> ні")
    void filter_noSnmp_isFiltered() {
        ZabbixProblem noSnmp = problem("r234-1", "No SNMP data collection for host r234-1");
        ZabbixProblem noSnmpUpper = problem("r234-1", "NO SNMP DATA COLLECTION for host r234-1");
        ZabbixProblem kept = problem("r234-1", "SNMP trap received from host r234-1");

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(noSnmp, noSnmpUpper, kept), List.of());

        assertEquals(List.of(kept), result);
    }

    // ---- OSPF ----

    @Test
    @DisplayName("Назва, що містить 'OSPF' -> відфільтровується (вже йде через OspfIncidentParser); "
            + "майже ідентична назва без OSPF -> ні")
    void filter_ospf_isFiltered() {
        ZabbixProblem ospf = problem("r234-1", "OSPF neighbor down");
        ZabbixProblem kept = problem("r234-1", "BGP neighbor down");

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(ospf, kept), List.of());

        assertEquals(List.of(kept), result);
    }

    // ---- Restart/reboot ----

    @Test
    @DisplayName("Назва 'restarted'/'rebooted' -> відфільтровується (вже йде через PdIncidentParser); "
            + "майже ідентична назва без цих слів -> ні")
    void filter_restart_isFiltered() {
        ZabbixProblem restarted = problem("r234-1", "Host has been restarted");
        ZabbixProblem rebooted = problem("r234-1", "Device rebooted unexpectedly");
        ZabbixProblem kept = problem("r234-1", "Host is up");

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(restarted, rebooted, kept), List.of());

        assertEquals(List.of(kept), result);
    }

    // ---- Дублікати IMAP: збіг за host (case-insensitive) і часом (~5 хв) ----

    @Test
    @DisplayName("Дублікат IMAP: збіг host і часу в межах допуску (300с, включно) -> відфільтровується")
    void filter_imapDuplicate_withinToleranceInclusive_isFiltered() {
        Incident imap = imapIncident("r234-1", 1000L);
        ZabbixProblem duplicate = problem("r234-1", "Link down", 1000L + 300L);

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(duplicate), List.of(imap));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Дублікат IMAP: розходження в часі щойно за межею допуску (301с) -> НЕ відфільтровується")
    void filter_imapDuplicate_justOutsideTolerance_isNotFiltered() {
        Incident imap = imapIncident("r234-1", 1000L);
        ZabbixProblem notDuplicate = problem("r234-1", "Link down", 1000L + 301L);

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(notDuplicate), List.of(imap));

        assertEquals(List.of(notDuplicate), result);
    }

    @Test
    @DisplayName("Дублікат IMAP: збіг host без урахування регістру (equalsIgnoreCase)")
    void filter_imapDuplicate_caseInsensitiveHostMatch() {
        Incident imap = imapIncident("R234-1", 1000L);
        ZabbixProblem duplicate = problem("r234-1", "Link down", 1000L);

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(duplicate), List.of(imap));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Дублікат IMAP: інцидент з порожнім device (напр. OSM) ніколи не рахується дублікатом, "
            + "навіть якщо час збігається")
    void filter_imapDuplicate_emptyDeviceNeverMatches() {
        Incident imapWithoutDevice = imapIncident("", 1000L);
        ZabbixProblem stillKept = problem("r234-1", "Link down", 1000L);

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(stillKept), List.of(imapWithoutDevice));

        assertEquals(List.of(stillKept), result);
    }

    @Test
    @DisplayName("Дублікат IMAP: інший host -> не дублікат, навіть якщо час точно збігається")
    void filter_imapDuplicate_differentHost_isNotFiltered() {
        Incident imap = imapIncident("s500-2", 1000L);
        ZabbixProblem differentHost = problem("r234-1", "Link down", 1000L);

        List<ZabbixProblem> result = ProblemFilter.filter(List.of(differentHost), List.of(imap));

        assertEquals(List.of(differentHost), result);
    }

    // ---- Комбінований сценарій: порядок збережено, усі правила разом ----

    @Test
    @DisplayName("Комбінований список: лише проблеми, що не потрапляють під жодне правило, залишаються "
            + "у вихідному порядку")
    void filter_mixedList_onlySurvivingProblemsKeptInOrder() {
        ZabbixProblem blank = problem("", "Link down");
        ZabbixProblem sdhOsm = problem("SDH-OSM", "Trap");
        ZabbixProblem noSnmp = problem("r1", "No SNMP data collection");
        ZabbixProblem ospf = problem("r2", "OSPF neighbor down");
        ZabbixProblem restarted = problem("r3", "Host restarted");
        ZabbixProblem duplicate = problem("r4", "Link down", 5000L);
        ZabbixProblem survivorA = problem("r5", "Power supply failure", 100L);
        ZabbixProblem survivorB = problem("r6", "Link down", 200L);

        Incident imapDup = imapIncident("r4", 5000L);

        List<ZabbixProblem> result = ProblemFilter.filter(
                List.of(blank, sdhOsm, noSnmp, ospf, restarted, duplicate, survivorA, survivorB),
                List.of(imapDup));

        assertEquals(List.of(survivorA, survivorB), result);
    }
}
