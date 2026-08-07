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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import net.ukrcom.noczvit.imap.RawMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RamosTrapParserTest {

    // toInstant()/подальші перевірки залежать від ZoneId.systemDefault() — фіксуємо зону без
    // DST-сюрпризів, як у DateUtilsTest.
    private static TimeZone originalDefault;

    @BeforeAll
    static void fixDefaultZone() {
        originalDefault = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Kyiv"));
    }

    @AfterAll
    static void restoreDefaultZone() {
        TimeZone.setDefault(originalDefault);
    }

    private static final long SAMPLE_EPOCH =
            LocalDateTime.of(2026, 8, 5, 13, 37, 15).atZone(ZoneId.systemDefault()).toEpochSecond();

    private static RawMessage ramosMessage(String body) {
        return new RawMessage("Wed, 5 Aug 2026 13:37:20 +0300", SAMPLE_EPOCH, "Got trap from ramos", body, "");
    }

    private static RawMessage ramosMessage(String subject, String body) {
        return new RawMessage("Wed, 5 Aug 2026 13:37:20 +0300", SAMPLE_EPOCH, subject, body, "");
    }

    private static String trapLine(String state, String sensorName, String sensorType) {
        return "\t\"" + state + "\" / \"" + sensorName + "\" / \"" + sensorType + "\"\n";
    }

    private static String trapBody(String state, String sensorName, String sensorType) {
        return "At 05-08-2026 13:37:15, from 10.0.0.5, after uptime 5:00:00.000, registered trap:\n"
                + trapLine(state, sensorName, sensorType);
    }

    // --- базовий (не-hex) розбір ---

    @Test
    void parse_basicNonHexBody_extractsAllFields() {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("High Critical", "Sensor1", "Dry Contact N.M"))));

        assertEquals(1, events.size());
        RamosTrapEvent ev = events.get(0);
        assertEquals("10.0.0.5", ev.ip());
        assertEquals("High Critical", ev.state());
        assertEquals("Sensor1", ev.sensorName());
        assertEquals("Dry Contact N.M", ev.sensorType());
        assertEquals("Інші", ev.room());
        assertEquals(LocalDateTime.of(2026, 8, 5, 13, 37, 15)
                .atZone(ZoneId.systemDefault()).toInstant(), ev.timestamp());
    }

    @Test
    void parse_subjectNotRamos_isSkipped() {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage("Got trap from celsius", trapBody("Critical", "Sensor1", "Dry Contact N.M"))));
        assertTrue(events.isEmpty());
    }

    @Test
    void parse_subjectIsCaseInsensitive() {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage("GOT TRAP FROM RAMOS", trapBody("Critical", "Sensor1", "Dry Contact N.M"))));
        assertEquals(1, events.size());
    }

    @Test
    void parse_emptyMessageList_returnsEmpty() {
        assertTrue(RamosTrapParser.parse(List.of()).isEmpty());
    }

    @Test
    void parse_blankBody_returnsEmpty() {
        assertTrue(RamosTrapParser.parse(List.of(ramosMessage("   "))).isEmpty());
    }

    @Test
    void parse_multipleTrapsInOneMessage_parsesBoth() {
        String body = trapBody("High Critical", "Sensor1", "Dry Contact N.M")
                + trapBody("Warning", "Sensor2", "Dual Temperature N");
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(ramosMessage(body)));

        assertEquals(2, events.size());
        assertEquals("Sensor1", events.get(0).sensorName());
        assertEquals("Sensor2", events.get(1).sensorName());
    }

    // --- фільтрація станів (REPORTABLE_STATES) ---

    @ParameterizedTest
    @ValueSource(strings = {"Normal", "Connect", "Disconnect"})
    void parse_nonReportableState_isDropped(String state) {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody(state, "Sensor1", "Dry Contact N.M"))));
        assertTrue(events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Critical", "High Critical", "Low Critical",
        "High Warning", "Low Warning", "Warning", "Sensor Error"})
    void parse_reportableState_isKept(String state) {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody(state, "Sensor1", "Dry Contact N.M"))));
        assertEquals(1, events.size());
        assertEquals(state, events.get(0).state());
    }

    // --- hex-декодування назв датчиків: межа 3 vs 4 байтові групи ---

    @Test
    void parse_hexDecoding_fourByteGroup_isDecodedToUtf8() {
        // D0 90 D0 9F -> "АП" (Cyrillic А + П), рівно 4 групи — межа спрацьовування regex.
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", "D0 90 D0 9F", "Dry Contact N.M"))));
        assertEquals(1, events.size());
        assertEquals("АП", events.get(0).sensorName());
    }

    @Test
    void parse_hexDecoding_threeByteGroupBelowThreshold_staysLiteral() {
        // "41 42 43" -- лише 3 групи, нижче порогу (потрібно мінімум 4) — лишається як є.
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", "41 42 43", "Dry Contact N.M"))));
        assertEquals(1, events.size());
        assertEquals("41 42 43", events.get(0).sensorName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AC", "DC DC"})
    void parse_hexDecoding_shortAbbreviationsThatLookLikeHex_stayLiteral(String sensorName) {
        // "AC" (кондиціонер) та "DC DC" -- валідний hex, але замало груп (1 і 2 відповідно),
        // тож НЕ декодуються — інакше перетворились би на нечитабельні символи.
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", sensorName, "Dry Contact N.M"))));
        assertEquals(1, events.size());
        assertEquals(sensorName, events.get(0).sensorName());
    }

    @Test
    void parse_hexDecoding_multilineDump_decodesAcrossLineBreak() {
        // Приклад із README: продовження hex-дампу на наступному рядку.
        String body = "At 05-08-2026 13:37:15, from 10.0.0.5, after uptime 5:00:00.000, registered trap:\n"
                + "\t\"High Critical\" / \"52 6F 6F 6D 34 20 D0 90 D0 9D D0 A2 D0 98 D0 9F D0\n"
                + " 9E D0 A2 D0 9E D0 9F 20 D0 92 D0 AB D0 A5 D0 9E D0 94 20 53 30 36\" / \"Dry Contact N.M\"\n";
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(ramosMessage(body)));

        assertEquals(1, events.size());
        // Декодоване ім'я містить "Room4", а сам вивід уже пройшов expandAbbreviations -> "зал 4".
        assertEquals("зал 4 АНТИПОТОП ВЫХОД S06", events.get(0).sensorName());
        assertEquals("Room4", events.get(0).room());
    }

    @Test
    void parse_hexDecoding_invalidUtf8Bytes_staysLiteralInsteadOfReplacementChars() {
        // 4+ груп валідного hex, але байти не утворюють коректний UTF-8 -> суворе декодування
        // (CodingErrorAction.REPORT) відкидає результат і лишає рядок як є.
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", "FF FF FF FF", "Dry Contact N.M"))));
        assertEquals(1, events.size());
        assertEquals("FF FF FF FF", events.get(0).sensorName());
    }

    // --- expandAbbreviations() та порядок extractRoom() ДО розгортання ---

    @Test
    void parse_expandAbbreviations_avrAndRoomAreCaseInsensitive() {
        // Точний приклад з README.
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", "Room3 AVR 1 INPUT POWER 1", "Dry Contact N.M"))));
        assertEquals(1, events.size());
        assertEquals("зал 3 автоматичний ввід резерву 1 INPUT POWER 1", events.get(0).sensorName());
        assertEquals("Room3", events.get(0).room());
    }

    @Test
    void parse_extractRoom_usesNameBeforeExpansion_expandedNameNoLongerContainsRoomToken() {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", "Room2 AVR", "Dry Contact N.M"))));
        assertEquals(1, events.size());
        RamosTrapEvent ev = events.get(0);

        // room виведено з ОРИГІНАЛЬНОЇ (до розгортання) назви.
        assertEquals("Room2", ev.room());
        // а розгорнута sensorName вже не містить "Room2" у впізнаваному вигляді.
        assertFalseContainsRoomToken(ev.sensorName());
        assertEquals("зал 2 автоматичний ввід резерву", ev.sensorName());
    }

    private static void assertFalseContainsRoomToken(String name) {
        assertTrue(!name.matches("(?is).*\\broom\\s*\\d.*"), "sensorName still contains a RoomN token: " + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Room 1", "room1", "ROOM  2", "Room3", "Room4"})
    void parse_extractRoom_variantsWithOptionalSpaceAndCase(String prefix) {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", prefix + " Sensor", "Dry Contact N.M"))));
        assertEquals(1, events.size());
        char digit = prefix.replaceAll("\\D", "").charAt(0);
        assertEquals("Room" + digit, events.get(0).room());
    }

    @Test
    void parse_extractRoom_nameWithoutRoom_isOther() {
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(
                ramosMessage(trapBody("Critical", "Water Sensor 1", "Water Detector"))));
        assertEquals(1, events.size());
        assertEquals("Інші", events.get(0).room());
    }

    // --- часова мітка ---

    @Test
    void parse_invalidTimestamp_skipsEventInsteadOfThrowing() {
        String body = "At 31-13-2026 13:37:15, from 10.0.0.5, after uptime 5:00:00.000, registered trap:\n"
                + trapLine("Critical", "Sensor1", "Dry Contact N.M");
        List<RamosTrapEvent> events = RamosTrapParser.parse(List.of(ramosMessage(body)));
        assertTrue(events.isEmpty());
    }
}
