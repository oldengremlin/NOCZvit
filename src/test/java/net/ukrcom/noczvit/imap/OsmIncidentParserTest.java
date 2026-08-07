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
package net.ukrcom.noczvit.imap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.TestFixtures;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Тести {@link OsmIncidentParser}: розбір теми SDH/OSM-листа (from/to, резолв через
 * {@link Dictionary#resolveSDH}, охорона {@code needsReview} для порожнього {@code to}),
 * визначення статусу через {@link net.ukrcom.noczvit.model.IncidentDescriptions#resolveStatus},
 * формування опису події та, найголовніше, вилучення точного часу події з рядка
 * {@code Trap value:} у тілі листа разом із порогом у 5 хвилин (300с) для нотатки
 * «який відбувся».
 */
class OsmIncidentParserTest {

    // Часова зона береться системна — так само, як DateUtils.toInstant/LocalDateTime.ofInstant
    // у самому парсері, щоб тести лишались коректними незалежно від TZ хосту, де запускається mvn.
    private static final ZoneId ZONE = ZoneId.systemDefault();

    // Час алерту (заголовок листа) — довільна дата, не прив'язана до реального року.
    private static final ZonedDateTime ALERT_ZDT = ZonedDateTime.of(2026, 8, 7, 12, 0, 0, 0, ZONE);
    private static final long ALERT_TS = ALERT_ZDT.toEpochSecond();
    private static final String ALERT_DATE_STR
            = ALERT_ZDT.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH));
    private static final String ALERT_DATE_LOC = DateUtils.convertMonthNumToMnemo(ALERT_DATE_STR);

    private static final String IN_REPLY_TO = "<osm-trap-12345@monitoring.test.invalid>";

    private static final Map<String, String> SDH = Map.of(
            "ATS-560", "АТС-560",
            "Borispolska11a", "Бориспільська 11а"
    );

    private static final String SUBJECT_STM_START
            = "[-] Problem: SDH-OSM: ATS-560__Borispolska11a ADMU STM STM1cLOS STM-1 Loss of input signal";
    private static final String SUBJECT_STM_END
            = "[+] Resolved: SDH-OSM: ATS-560__Borispolska11a ADMU STM STM1cLOS STM-1 Loss of input signal";
    private static final String SUBJECT_STM_NONE
            = "[?] Notice: SDH-OSM: ATS-560__Borispolska11a ADMU STM STM1cLOS STM-1 Loss of input signal";
    private static final String SUBJECT_STM_SINGLE_LOCATION
            = "[-] Problem: SDH-OSM: ATS-560 ADMU STM STM1cLOS STM-1 Loss of input signal";
    private static final String SUBJECT_POWER_REGULAR
            = "[-] Problem: SDH-OSM: ATS-560 ADMU Power Power_220 Power failure";
    private static final String SUBJECT_POWER_AIR_CONDITION
            = "[-] Problem: SDH-OSM: ATS-560 ADMU Power Air Condition failure";
    private static final String SUBJECT_OTHER_TYPE
            = "[-] Problem: SDH-OSM: ATS-560 ADMU Alarm SomeOther text";

    // ---- Допоміжні фабрики ----

    private static Dictionary sdhDictionary(Path tempDir) throws Exception {
        return TestFixtures.dictionary(tempDir, Map.of(), SDH, Map.of());
    }

    private static RawMessage osmMessage(String subject, String body) {
        return new RawMessage(ALERT_DATE_STR, ALERT_TS, subject, body, IN_REPLY_TO);
    }

    /** ISO-мітка часу у форматі, який шукає {@code PATTERN_DATE} парсера: yyyy-MM-dd'T'HH:mm:ss. */
    private static String isoAt(long epochSec) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZONE)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    /**
     * Тіло листа з рядком {@code Trap value:} у форматі, максимально наближеному до реального
     * OSM-повідомлення (два представлення часу в одному рядку — парсер бере лише ISO-частину).
     */
    private static String bodyWithTrapValue(String isoDateTime) {
        return "Some preamble line\r\n"
                + "Trap value: 11:55:00 2026/08/07 .1.3.6.1.4.1.8072.83.84.77.2.1.2 : - (D:00:00:00.00) "
                + isoDateTime + " ATS-560__Borispolska11a ADMU STM STM1cLOS STM-1 Loss of input signal\r\n"
                + "Trailer info";
    }

    /**
     * Очікуваний локалізований рядок дати події, побудований тим самим способом, що й приватний
     * {@code TRAP_DATE_OUTPUT_FORMATTER} у парсері («dd MMM yyyy HH:mm:ss Z», ENGLISH), а потім
     * той самий {@link DateUtils#convertMonthNumToMnemo} — використовується як оракул очікування,
     * а не для перевірки самого форматування (воно вже сама production-логіка).
     */
    private static String expectedTrapEventDateLoc(long epochSec) {
        String raw = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZONE)
                .atZone(ZONE)
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH));
        return DateUtils.convertMonthNumToMnemo(raw);
    }

    // ==================== Розбір теми: from/to, резолв SDH, needsReview ====================

    @Test
    @DisplayName("STM з обома локаціями: обидві резолвляться, review не потрібен")
    void stmSubject_bothLocationsResolved_noReviewNeeded(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_START, "")).orElseThrow();

        assertEquals("АТС-560", incident.location());
        assertTrue(incident.reviewNames().isEmpty());
        assertTrue(incident.description().contains("з АТС-560 на Бориспільська 11а"));
    }

    @Test
    @DisplayName("STM без '__' у geo: to порожній, охорона needsReviewTo не спрацьовує "
            + "навіть якщо resolveSDH(\"\") сам собою дав би needsReview=true")
    void stmSubject_noSeparator_toEmpty_reviewGuardApplies(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_SINGLE_LOCATION, "")).orElseThrow();

        assertEquals("АТС-560", incident.location());
        // Якби охорона !originalTo.isEmpty() була відсутня, "" не резолвилось би (value==key=="")
        // і review-список отримав би зайвий порожній рядок.
        assertTrue(incident.reviewNames().isEmpty());
        assertTrue(incident.description().contains("на АТС-560"));
    }

    @Test
    @DisplayName("Не-STM тип (Power): to завжди порожній, охорона needsReviewTo теж не спрацьовує")
    void nonStmSubject_toNeverPopulated_reviewGuardApplies(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_POWER_REGULAR, "")).orElseThrow();

        assertEquals("АТС-560", incident.location());
        assertTrue(incident.reviewNames().isEmpty());
    }

    @Test
    @DisplayName("from не резолвиться словником -> needsReviewFrom=true, потрапляє у reviewNames")
    void unresolvedFrom_addedToReviewNames(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(),
                Map.of("Borispolska11a", "Бориспільська 11а"), Map.of());
        OsmIncidentParser parser = new OsmIncidentParser(dictionary);

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_START, "")).orElseThrow();

        assertEquals("ATS-560", incident.location());
        assertEquals(List.of("ATS-560"), incident.reviewNames());
    }

    @Test
    @DisplayName("to непорожній і не резолвиться словником -> needsReviewTo=true, потрапляє у reviewNames")
    void unresolvedNonEmptyTo_addedToReviewNames(@TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(),
                Map.of("ATS-560", "АТС-560"), Map.of());
        OsmIncidentParser parser = new OsmIncidentParser(dictionary);

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_START, "")).orElseThrow();

        assertEquals(List.of("Borispolska11a"), incident.reviewNames());
    }

    @Test
    @DisplayName("from і to одночасно не резолвляться -> обидва у reviewNames, порядок from потім to")
    void bothUnresolved_orderPreservedInReviewNames(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(TestFixtures.dictionary(tempDir, Map.of(), Map.of(), Map.of()));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_START, "")).orElseThrow();

        assertEquals(List.of("ATS-560", "Borispolska11a"), incident.reviewNames());
    }

    // ==================== resolveStatus ====================

    @Test
    @DisplayName("Тема з '[-] Problem:' -> Status.START (делегування в IncidentDescriptions.resolveStatus)")
    void status_problemSubject_isStart(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_START, "")).orElseThrow();

        assertEquals(Status.START, incident.status());
        assertTrue(incident.description().startsWith("OSM зареєстровано початок інциденту, "));
    }

    @Test
    @DisplayName("Тема з '[+] Resolved:' -> Status.END")
    void status_resolvedSubject_isEnd(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_END, "")).orElseThrow();

        assertEquals(Status.END, incident.status());
        assertTrue(incident.description().startsWith("OSM зареєстровано кінець інциденту, "));
    }

    @Test
    @DisplayName("Тема без Problem/Resolved -> Status.NONE, опис через noneText='інцидент, '")
    void status_neitherKeyword_isNone(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_NONE, "")).orElseThrow();

        assertEquals(Status.NONE, incident.status());
        assertTrue(incident.description().startsWith("OSM зареєстровано інцидент, "));
    }

    // ==================== Формування опису події ====================

    @Test
    @DisplayName("Опис STM з двома локаціями: 'втрата зв'язності з X на Y'")
    void description_stmTwoLocations(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_START, "")).orElseThrow();

        assertEquals("OSM зареєстровано початок інциденту, втрата зв'язності з АТС-560 на Бориспільська 11а",
                incident.description());
    }

    @Test
    @DisplayName("Опис STM з однією локацією: 'втрата зв'язності на X'")
    void description_stmSingleLocation(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_SINGLE_LOCATION, "")).orElseThrow();

        assertEquals("OSM зареєстровано початок інциденту, втрата зв'язності на АТС-560", incident.description());
    }

    @Test
    @DisplayName("Опис Power (звичайний винос): 'зникнення живлення на виносі X'")
    void description_powerRegular(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_POWER_REGULAR, "")).orElseThrow();

        assertEquals("OSM зареєстровано початок інциденту, зникнення живлення на виносі АТС-560",
                incident.description());
    }

    @Test
    @DisplayName("Опис Power + Air Condition: 'зникнення живлення на X до кондиціонерів' (без 'виносі')")
    void description_powerAirCondition(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_POWER_AIR_CONDITION, "")).orElseThrow();

        assertEquals("OSM зареєстровано початок інциденту, зникнення живлення на АТС-560 до кондиціонерів",
                incident.description());
    }

    @Test
    @DisplayName("Тип, відмінний і від Power, і від STM: трактується як 'втрата зв'язності' (else-гілка)")
    void description_otherType_treatedAsConnectivityLoss(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_OTHER_TYPE, "")).orElseThrow();

        assertEquals("OSM зареєстровано початок інциденту, втрата зв'язності АТС-560", incident.description());
    }

    // ==================== Час події з "Trap value" — поріг 5 хвилин (300с) ====================

    @ParameterizedTest(name = "лаг {0}с (< 300 = шум годинників, >= 300 = нотатка) -> нотатка додається: {1}")
    @CsvSource({
        "299, false",
        "300, true",
        "301, true"
    })
    @DisplayName("Межа TRAP_NOTE_MIN_LAG_SEC (300с) інклюзивна знизу для нотатки «який відбувся»")
    void trapValueLag_boundaryAtThreshold(long lagSeconds, boolean expectNote, @TempDir Path tempDir) throws Exception {
        // eventTs завжди зберігає Trap value «як є» (тут < alert, тому клампінгу не буде) —
        // від порогу залежить ЛИШЕ те, чи додається текстова нотатка в description.
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        long trapTs = ALERT_TS - lagSeconds;
        RawMessage msg = osmMessage(SUBJECT_STM_START, bodyWithTrapValue(isoAt(trapTs)));

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(trapTs, incident.eventTs());
        if (expectNote) {
            String expectedSuffix = ", який відбувся " + expectedTrapEventDateLoc(trapTs);
            assertTrue(incident.description().endsWith(expectedSuffix),
                    () -> "лаг " + lagSeconds + "с >= 300с — нотатка ОБОВ'ЯЗКОВА: " + incident.description());
        } else {
            assertFalse(incident.description().contains("який відбувся"),
                    () -> "лаг " + lagSeconds + "с < 300с — це шум годинників, нотатки БУТИ НЕ ПОВИННО: "
                    + incident.description());
        }
    }

    @Test
    @DisplayName("Trap value рівно на 300с раніше за alert: eventTs = alert - 300, нотатка присутня "
            + "(перевірка інклюзивності межі окремим non-parameterized тестом)")
    void trapValueLag_exactlyAtThreshold_noteIncluded(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        long trapTs = ALERT_TS - 300;
        RawMessage msg = osmMessage(SUBJECT_STM_START, bodyWithTrapValue(isoAt(trapTs)));

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(trapTs, incident.eventTs());
        assertTrue(incident.description().contains("який відбувся"));
    }

    @Test
    @DisplayName("Trap value ПІЗНІШЕ за alert: клампається до часу alert, уточнення не додається")
    void trapValueAfterAlert_clampedToAlertTime_noNote(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        long futureTrapTs = ALERT_TS + 120;
        RawMessage msg = osmMessage(SUBJECT_STM_START, bodyWithTrapValue(isoAt(futureTrapTs)));

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(ALERT_TS, incident.eventTs(), "подія не може статись пізніше за алерт — клампінг до alert");
        assertEquals(ALERT_DATE_LOC, incident.eventDateStr());
        assertFalse(incident.description().contains("який відбувся"));
    }

    @Test
    @DisplayName("Trap value відсутній у тілі -> eventTs = час alert, без уточнення")
    void trapValueAbsent_eventTsEqualsAlertTs_noNote(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        RawMessage msg = osmMessage(SUBJECT_STM_START, "Some unrelated body\r\nNo trap line here");

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(ALERT_TS, incident.eventTs());
        assertEquals(ALERT_DATE_LOC, incident.eventDateStr());
        assertFalse(incident.description().contains("який відбувся"));
    }

    @Test
    @DisplayName("Рядок 'Trap value:' є, але без ISO-підрядка yyyy-MM-ddTHH:mm:ss -> "
            + "matcher.find()==false, ts лишається часом alert")
    void trapValueLine_withoutIsoDate_fallsBackToAlertTs(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        String body = "Trap value: some garbage without any parseable date at all";
        RawMessage msg = osmMessage(SUBJECT_STM_START, body);

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(ALERT_TS, incident.eventTs());
        assertFalse(incident.description().contains("який відбувся"));
    }

    @Test
    @DisplayName("Рядок 'Trap value:' з ISO-підрядком, який синтаксично збігається з regex, "
            + "але семантично невалідний (місяць 13) -> DateTimeParseException зловлений, "
            + "ts лишається часом alert")
    void trapValueLine_invalidDate_parseExceptionCaught_fallsBackToAlertTs(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        String body = "Trap value: 11:55:00 2026/08/07 .1.3.6.1.4.1.8072 : - (D:00:00:00.00) "
                + "2026-13-05T10:00:00 ATS-560__Borispolska11a ADMU STM STM1cLOS STM-1 Loss of input signal";
        RawMessage msg = osmMessage(SUBJECT_STM_START, body);

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(ALERT_TS, incident.eventTs());
        assertEquals(ALERT_DATE_LOC, incident.eventDateStr());
        assertFalse(incident.description().contains("який відбувся"));
    }

    @Test
    @DisplayName("Кілька рядків 'Trap value:' у тілі — обробляється лише ПЕРШИЙ (break одразу "
            + "після першого збігу startsWith, незалежно від успіху парсингу дати в ньому); "
            + "пінінг поточної поведінки")
    void multipleTrapValueLines_onlyFirstOneConsidered(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        long secondLineTs = ALERT_TS - 3600; // мав би дати лаг набагато більший за поріг
        String body = "Trap value: no date in the first occurrence at all\r\n"
                + "Trap value: 11:55:00 2026/08/07 .1.3.6.1.4.1.8072 : - (D:00:00:00.00) "
                + isoAt(secondLineTs) + " ATS-560__Borispolska11a ADMU STM STM1cLOS STM-1 Loss of input signal";
        RawMessage msg = osmMessage(SUBJECT_STM_START, body);

        Incident incident = parser.parse(msg).orElseThrow();

        // Другий (валідний) рядок ІГНОРУЄТЬСЯ — парсер зупиняється на першому "Trap value:" рядку.
        assertEquals(ALERT_TS, incident.eventTs());
        assertFalse(incident.description().contains("який відбувся"));
    }

    @Test
    @DisplayName("CRLF у тілі листа не заважає знайти Trap value (body.replace(\"\\r\", \"\"))")
    void trapValueLine_crlfBody_stillParsed(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        long trapTs = ALERT_TS - 600; // 10 хвилин — над порогом
        RawMessage msg = osmMessage(SUBJECT_STM_START, bodyWithTrapValue(isoAt(trapTs)));

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(trapTs, incident.eventTs());
        assertTrue(incident.description().contains("який відбувся"));
    }

    // ==================== Наскрізні поля Incident ====================

    @Test
    @DisplayName("messageTs завжди дорівнює msg.unixDate(), незалежно від Trap value")
    void messageTs_alwaysEqualsMessageUnixDate(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));
        long trapTs = ALERT_TS - 3600;
        RawMessage msg = osmMessage(SUBJECT_STM_START, bodyWithTrapValue(isoAt(trapTs)));

        Incident incident = parser.parse(msg).orElseThrow();

        assertEquals(ALERT_TS, incident.messageTs());
        assertEquals(trapTs, incident.eventTs());
        assertTrue(incident.messageTs() != incident.eventTs());
    }

    @Test
    @DisplayName("device завжди порожній рядок, source завжди OSM, inReplyTo передається без змін")
    void passthroughFields(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Incident incident = parser.parse(osmMessage(SUBJECT_STM_START, "")).orElseThrow();

        assertEquals("", incident.device());
        assertEquals(Source.OSM, incident.source());
        assertEquals(IN_REPLY_TO, incident.inReplyTo());
    }

    @Test
    @DisplayName("Занадто коротка/невідповідна тема не кидає виключення — geo/type порожні, "
            + "парсер повертає Incident з порожньою локацією")
    void malformedShortSubject_doesNotThrow(@TempDir Path tempDir) throws Exception {
        OsmIncidentParser parser = new OsmIncidentParser(sdhDictionary(tempDir));

        Optional<Incident> result = parser.parse(osmMessage("Bad Subject", ""));

        assertTrue(result.isPresent());
        assertEquals("", result.get().location());
        assertEquals(Status.NONE, result.get().status());
    }
}
