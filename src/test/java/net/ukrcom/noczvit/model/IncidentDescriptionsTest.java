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
package net.ukrcom.noczvit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.ukrcom.noczvit.model.Incident.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Тести {@link IncidentDescriptions}: розпізнавання статусу з теми листа, побудова префікса
 * ({@code statePrefix}) для всіх джерел/статусів та збірка повного опису ({@code describe}),
 * включно з нормалізацією пробілів.
 */
class IncidentDescriptionsTest {

    // ---- resolveStatus ----

    @Test
    @DisplayName("resolveStatus: тема з ' Problem:' -> START")
    void resolveStatus_problem_returnsStart() {
        assertEquals(Status.START, IncidentDescriptions.resolveStatus("[-] Problem: host is down"));
    }

    @Test
    @DisplayName("resolveStatus: тема з ' Resolved:' -> END")
    void resolveStatus_resolved_returnsEnd() {
        assertEquals(Status.END, IncidentDescriptions.resolveStatus("[+] Resolved: host is down"));
    }

    @Test
    @DisplayName("resolveStatus: тема без відомих маркерів -> NONE")
    void resolveStatus_unknownSubject_returnsNone() {
        assertEquals(Status.NONE, IncidentDescriptions.resolveStatus("Просто інформаційний лист"));
    }

    @Test
    @DisplayName("resolveStatus: ' Resolved:' перевіряється першим — коли темa (гіпотетично) "
            + "містить обидва маркери, перемагає END")
    void resolveStatus_bothMarkersPresent_resolvedWins() {
        String subject = "[-] Problem: was firing, now [+] Resolved: host is up";
        assertEquals(Status.END, IncidentDescriptions.resolveStatus(subject));
    }

    @Test
    @DisplayName("resolveStatus: маркер без пробілу перед ним не розпізнається (потрібен саме ' Problem:')")
    void resolveStatus_markerWithoutLeadingSpace_notRecognized() {
        // "xProblem:" не містить підрядка " Problem:" (з пробілом) -> NONE
        assertEquals(Status.NONE, IncidentDescriptions.resolveStatus("xProblem: host is down"));
    }

    // ---- statePrefix: 2-arg (noneText="") ----

    @Test
    @DisplayName("statePrefix(Zabbix, START): точний текст 'Zabbix зареєстровано початок інциденту, '")
    void statePrefix_zabbixStart_exactText() {
        assertEquals("Zabbix зареєстровано початок інциденту, ",
                IncidentDescriptions.statePrefix(IncidentDescriptions.SOURCE_ZABBIX, Status.START));
    }

    @Test
    @DisplayName("statePrefix(Zabbix, END): точний текст 'Zabbix зареєстровано кінець інциденту, '")
    void statePrefix_zabbixEnd_exactText() {
        assertEquals("Zabbix зареєстровано кінець інциденту, ",
                IncidentDescriptions.statePrefix(IncidentDescriptions.SOURCE_ZABBIX, Status.END));
    }

    @Test
    @DisplayName("statePrefix(OSM, START): точний текст 'OSM зареєстровано початок інциденту, '")
    void statePrefix_osmStart_exactText() {
        assertEquals("OSM зареєстровано початок інциденту, ",
                IncidentDescriptions.statePrefix(IncidentDescriptions.SOURCE_OSM, Status.START));
    }

    @Test
    @DisplayName("statePrefix(OSM, END): точний текст 'OSM зареєстровано кінець інциденту, '")
    void statePrefix_osmEnd_exactText() {
        assertEquals("OSM зареєстровано кінець інциденту, ",
                IncidentDescriptions.statePrefix(IncidentDescriptions.SOURCE_OSM, Status.END));
    }

    @Test
    @DisplayName("statePrefix(Zabbix, NONE), 2-arg: 'Zabbix зареєстровано ' без noneText")
    void statePrefix_zabbixNone_twoArg_emptyNoneText() {
        assertEquals("Zabbix зареєстровано ",
                IncidentDescriptions.statePrefix(IncidentDescriptions.SOURCE_ZABBIX, Status.NONE));
    }

    @Test
    @DisplayName("statePrefix(OSM, NONE), 3-arg: явний noneText 'інцидент, ' додається як є")
    void statePrefix_osmNone_explicitNoneText() {
        assertEquals("OSM зареєстровано інцидент, ",
                IncidentDescriptions.statePrefix(IncidentDescriptions.SOURCE_OSM, Status.NONE, "інцидент, "));
    }

    @ParameterizedTest(name = "джерело={0}, статус={1} -> ''{2}''")
    @CsvSource({
        "Zabbix, START, 'Zabbix зареєстровано початок інциденту, '",
        "Zabbix, END, 'Zabbix зареєстровано кінець інциденту, '",
        "Zabbix, NONE, 'Zabbix зареєстровано '",
        "OSM, START, 'OSM зареєстровано початок інциденту, '",
        "OSM, END, 'OSM зареєстровано кінець інциденту, '",
        "OSM, NONE, 'OSM зареєстровано '"
    })
    @DisplayName("statePrefix, 2-arg (усі 2 джерела x усі 3 статуси): точний очікуваний текст")
    void statePrefix_allSourcesAllStatuses_twoArg(String source, Status status, String expected) {
        assertEquals(expected, IncidentDescriptions.statePrefix(source, status));
    }

    // ---- describe ----

    @Test
    @DisplayName("describe: збирає префікс + подію в один рядок")
    void describe_buildsFullDescription() {
        String result = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_ZABBIX, Status.START,
                "зникнення зв'язку з обладнанням на Прахових 50");
        assertEquals("Zabbix зареєстровано початок інциденту, зникнення зв'язку з обладнанням на Прахових 50",
                result);
    }

    @Test
    @DisplayName("describe: нормалізує послідовність з кількох пробілів у подвійні/потрійні пробіли на один")
    void describe_collapsesMultipleSpaces() {
        String result = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_ZABBIX, Status.END,
                "подія   з     зайвими\tпробілами");
        assertEquals("Zabbix зареєстровано кінець інциденту, подія з зайвими пробілами", result);
    }

    @Test
    @DisplayName("describe: нормалізація пробілів охоплює і префікс, і подію разом (склеєні у стик)")
    void describe_normalizesAcrossPrefixAndEventBoundary() {
        // Подія починається з пробілів — після конкатенації префікс+event матиме подвійний пробіл
        // на межі (кома-пробіл із префікса + пробіли з event), який має схлопнутись в один.
        String result = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_ZABBIX, Status.START,
                "   зайвий пробіл на початку події");
        assertEquals("Zabbix зареєстровано початок інциденту, зайвий пробіл на початку події", result);
    }

    @Test
    @DisplayName("describe, 4-arg з noneText: NONE-статус OSM використовує явний noneText")
    void describe_noneStatus_withExplicitNoneText() {
        String result = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_OSM, Status.NONE,
                "аварійне живлення відновлено", "інцидент, ");
        assertEquals("OSM зареєстровано інцидент, аварійне живлення відновлено", result);
    }

    @Test
    @DisplayName("describe, 3-arg (без noneText): NONE-статус дає лише 'джерело зареєстровано ' + подія")
    void describe_noneStatus_withoutNoneText() {
        String result = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_ZABBIX, Status.NONE,
                "інформаційне повідомлення");
        assertEquals("Zabbix зареєстровано інформаційне повідомлення", result);
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    @DisplayName("describe: для кожного статусу опис завжди починається з 'Zabbix зареєстровано '")
    void describe_allStatuses_startWithSourcePrefix(Status status) {
        String result = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_ZABBIX, status, "подія");
        org.junit.jupiter.api.Assertions.assertTrue(result.startsWith("Zabbix зареєстровано "));
    }

    @Test
    @DisplayName("describe: порожня подія все одно дає коректний (обрізаний нормалізацією) префікс")
    void describe_emptyEvent() {
        String result = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_ZABBIX, Status.START, "");
        // "Zabbix зареєстровано початок інциденту, " + "" -> кінцевий пробіл лишається одинарним
        assertEquals("Zabbix зареєстровано початок інциденту, ", result);
    }
}
