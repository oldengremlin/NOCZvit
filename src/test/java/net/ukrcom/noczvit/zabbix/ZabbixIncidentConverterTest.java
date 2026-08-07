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

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.TestFixtures;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Тести {@link ZabbixIncidentConverter}: резолв локації/device-word, кількість інцидентів
 * (1 для активної проблеми, 2 для вирішеної), формат опису, {@code pairKey} та
 * {@code adlink}-специфічний резолв "card N, port N, line N" через PD-словник.
 */
class ZabbixIncidentConverterTest {

    private static String expectedDateStr(long epochSec) {
        LocalDateTime dt = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDateTime();
        return DateUtils.formatUa(dt);
    }

    // ---- Кількість інцидентів: 1 для активної, 2 для вирішеної ----

    @Test
    @DisplayName("convert: активна проблема (rClock=0) -> рівно один START-інцидент")
    void convert_activeProblem_returnsSingleStartIncident(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^r234-1$", "Обухів"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 0L);

        List<Incident> result = converter.convert(p);

        assertEquals(1, result.size());
        assertEquals(Status.START, result.get(0).status());
    }

    @Test
    @DisplayName("convert: вирішена проблема (rClock!=0) -> два інциденти, START перед END")
    void convert_resolvedProblem_returnsStartThenEndIncidents(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^r234-1$", "Обухів"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 1_300L);

        List<Incident> result = converter.convert(p);

        assertEquals(2, result.size());
        assertEquals(Status.START, result.get(0).status());
        assertEquals(Status.END, result.get(1).status());
    }

    // ---- Резолв локації через dictionary.resolvePD ----

    @Test
    @DisplayName("convert: host резолвиться у PD-словнику -> location = значення словника, reviewNames порожній")
    void convert_locationResolved_reviewNamesEmpty(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^r234-1$", "Обухів, Малишка 2"),
                Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertEquals("Обухів, Малишка 2", incident.location());
        assertTrue(incident.reviewNames().isEmpty());
    }

    @Test
    @DisplayName("convert: host НЕ резолвиться у PD-словнику -> location = сам host, reviewNames = [host]")
    void convert_locationUnresolved_reviewNamesContainsHost(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^nomatch$", "x"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("unknown-host", "Link down", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertEquals("unknown-host", incident.location());
        assertEquals(List.of("unknown-host"), incident.reviewNames());
    }

    // ---- Device-word у описі ----

    @Test
    @DisplayName("convert: device-word знайдено -> вставляється між 'на' і локацією з одним пробілом")
    void convert_deviceWordPresent_includedInDescription(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^r234-1$", "Обухів"), Map.of(),
                Map.of("^r", "маршрутизаторі"));
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertEquals("Zabbix зареєстровано початок інциденту, Link down на маршрутизаторі Обухів",
                incident.description());
    }

    @Test
    @DisplayName("convert: device-word відсутнє -> без подвійного пробілу, одразу 'на' + локація")
    void convert_deviceWordAbsent_noExtraSpaceInDescription(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^r234-1$", "Обухів"), Map.of(),
                Map.of("^s", "комутаторі"));
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertEquals("Zabbix зареєстровано початок інциденту, Link down на Обухів", incident.description());
    }

    // ---- Source, status prefix, статус START/END ----

    @Test
    @DisplayName("convert: source завжди Incident.Source.ZABBIX")
    void convert_source_isZabbix(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 1_300L);

        List<Incident> result = converter.convert(p);

        assertEquals(Source.ZABBIX, result.get(0).source());
        assertEquals(Source.ZABBIX, result.get(1).source());
    }

    @Test
    @DisplayName("convert: END-інцидент має префікс 'кінець інциденту' у описі")
    void convert_endIncident_hasEndPrefix(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^r234-1$", "Обухів"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 1_300L);

        Incident end = converter.convert(p).get(1);

        assertEquals("Zabbix зареєстровано кінець інциденту, Link down на Обухів", end.description());
    }

    // ---- pairKey ----

    @Test
    @DisplayName("convert: pairKey = 'zabbix:<host>:<clock>' та однаковий для START і END")
    void convert_pairKey_formatAndSharedBetweenStartAndEnd(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 1_300L);

        List<Incident> result = converter.convert(p);

        assertEquals("zabbix:r234-1:1000", result.get(0).inReplyTo());
        assertEquals("zabbix:r234-1:1000", result.get(1).inReplyTo());
    }

    // ---- Часові поля: START по clock, END по rClock ----

    @Test
    @DisplayName("convert: START використовує clock, END використовує rClock (messageTs=eventTs=відповідний час)")
    void convert_timestamps_startUsesClock_endUsesRClock(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 1_300L);

        List<Incident> result = converter.convert(p);
        Incident start = result.get(0);
        Incident end = result.get(1);

        assertEquals(1_000L, start.messageTs());
        assertEquals(1_000L, start.eventTs());
        assertEquals(expectedDateStr(1_000L), start.messageDateStr());
        assertEquals(expectedDateStr(1_000L), start.eventDateStr());

        assertEquals(1_300L, end.messageTs());
        assertEquals(1_300L, end.eventTs());
        assertEquals(expectedDateStr(1_300L), end.messageDateStr());
        assertEquals(expectedDateStr(1_300L), end.eventDateStr());
    }

    @Test
    @DisplayName("convert: device-поле інциденту = сирий host, а не резолвлена локація")
    void convert_device_isRawHost(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^r234-1$", "Обухів"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r234-1", "Link down", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertEquals("r234-1", incident.device());
    }

    // ---- resolveEventDesc: тільки для host, що починається з "adlink" ----

    @Test
    @DisplayName("resolveEventDesc: host НЕ починається з 'adlink' -> назва не змінюється, "
            + "навіть якщо збігається з патерном 'card N, port N, line N'")
    void resolveEventDesc_nonAdlinkHost_nameUnchangedEvenIfMatchesPattern(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir,
                Map.of("^r1:1:2:3$", "НЕ МАЄ ЗАСТОСУВАТИСЬ"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("r1", "card 1, port 2, line 3", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertTrue(incident.description().contains("card 1, port 2, line 3"));
    }

    @Test
    @DisplayName("resolveEventDesc: host починається з 'adlink', але назва НЕ збігається з патерном "
            + "-> назва не змінюється")
    void resolveEventDesc_adlinkHost_noPatternMatch_nameUnchanged(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("adlink1", "Generic alarm text", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertTrue(incident.description().contains("Generic alarm text"));
    }

    @Test
    @DisplayName("resolveEventDesc: host 'adlink*' + патерн збігається + запис у PD-словнику є "
            + "-> опис береться зі словника (device:card:port:line)")
    void resolveEventDesc_adlinkHost_patternMatch_resolved_usesDictionaryValue(@TempDir Path tempDir)
            throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(
                "^adlink1:1:2:3$", "Розмикання контакту дверей",
                "^adlink1$", "Прахові"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("adlink1", "Alarm: card 1, port 2, line 3 changed", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertTrue(incident.description().contains("Розмикання контакту дверей"),
                () -> "опис мав містити резолвлений текст: " + incident.description());
        assertTrue(!incident.description().contains("Alarm: card 1, port 2, line 3 changed"));
    }

    @Test
    @DisplayName("resolveEventDesc: host 'adlink*' + патерн збігається, але запису у PD-словнику НЕМАЄ "
            + "-> fallback на оригінальну назву")
    void resolveEventDesc_adlinkHost_patternMatch_unresolved_fallsBackToOriginalName(@TempDir Path tempDir)
            throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of("^adlink1$", "Прахові"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("adlink1", "Alarm: card 1, port 2, line 3 changed", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertTrue(incident.description().contains("Alarm: card 1, port 2, line 3 changed"));
    }

    @Test
    @DisplayName("resolveEventDesc: 'startsWith(\"adlink\")' регістрозалежний -> 'Adlink1' (з великої літери) "
            + "НЕ трактується як adlink-хост, резолв card/port/line не застосовується")
    void resolveEventDesc_startsWithIsCaseSensitive(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir,
                Map.of("^Adlink1:1:2:3$", "НЕ МАЄ ЗАСТОСУВАТИСЬ"), Map.of(), Map.of());
        ZabbixIncidentConverter converter = new ZabbixIncidentConverter(dictionary);
        ZabbixProblem p = new ZabbixProblem("Adlink1", "card 1, port 2, line 3", 1_000L, 0L);

        Incident incident = converter.convert(p).get(0);

        assertTrue(incident.description().contains("card 1, port 2, line 3"));
    }
}
