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
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.ukrcom.noczvit.trap.RamosTrapSection.SectionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RamosTrapSectionTest {

    // DateUtils.formatUa(Instant) читає ZoneId.systemDefault() — фіксуємо зону.
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

    private static final Instant T1 = Instant.parse("2026-08-05T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-05T11:00:00Z");
    private static final Instant T3 = Instant.parse("2026-08-05T12:00:00Z");
    private static final Instant T4 = Instant.parse("2026-08-05T13:00:00Z");

    private static RamosTrapEvent ev(Instant ts, String state, String sensorName, String room) {
        return new RamosTrapEvent(ts, "10.0.0.5", state, sensorName, "Dry Contact N.M", room);
    }

    private final RamosTrapSection section = new RamosTrapSection();

    // --- порожній список ---

    @Test
    void build_emptyEventList_returnsEmptySectionResult() {
        SectionResult result = section.build(List.of());
        assertTrue(result.isEmpty());
        assertEquals("", result.html());
        assertEquals("", result.plainText());
    }

    // --- групування по кімнатах: порядок ---

    @Test
    void build_roomsInAlphabeticalOrder_othersAlwaysLast() {
        List<RamosTrapEvent> events = List.of(
                ev(T1, "Critical", "SensorInOther", "Інші"),
                ev(T1, "Critical", "SensorInRoom3", "Room3"),
                ev(T1, "Critical", "SensorInRoom1", "Room1"),
                ev(T1, "Critical", "SensorInRoom2", "Room2"));

        String html = section.build(events).html();

        int i1 = html.indexOf(">Room1<");
        int i2 = html.indexOf(">Room2<");
        int i3 = html.indexOf(">Room3<");
        int iOther = html.indexOf(">Інші<");

        assertTrue(i1 >= 0 && i2 >= 0 && i3 >= 0 && iOther >= 0);
        assertTrue(i1 < i2, "Room1 має йти перед Room2");
        assertTrue(i2 < i3, "Room2 має йти перед Room3");
        assertTrue(i3 < iOther, "Інші має йти останнім, навіть попри те що не в кінці алфавіту серед лишків");
    }

    @Test
    void build_othersLast_evenWhenOnlyOtherAndOneRoomPresent() {
        List<RamosTrapEvent> events = List.of(
                ev(T1, "Critical", "SensorInOther", "Інші"),
                ev(T1, "Critical", "SensorInRoom4", "Room4"));

        String html = section.build(events).html();
        assertTrue(html.indexOf(">Room4<") < html.indexOf(">Інші<"));
    }

    // --- сортування за часом усередині кімнати ---

    @Test
    void build_withinRoom_sortedByTimestampRegardlessOfInputOrder() {
        List<RamosTrapEvent> events = List.of(
                ev(T3, "Critical", "Later", "Room1"),
                ev(T1, "Critical", "Earlier", "Room1"),
                ev(T2, "Critical", "Middle", "Room1"));

        String html = section.build(events).html();
        int iEarlier = html.indexOf("Earlier");
        int iMiddle = html.indexOf("Middle");
        int iLater = html.indexOf("Later");

        assertTrue(iEarlier < iMiddle);
        assertTrue(iMiddle < iLater);
    }

    // --- наскрізна нумерація через усі кімнати ---

    @Test
    void build_numberingIsContinuousAcrossRoomTables_doesNotResetPerRoom() {
        List<RamosTrapEvent> events = List.of(
                ev(T1, "Critical", "Room1SensorA", "Room1"),
                ev(T2, "Critical", "Room1SensorB", "Room1"),
                ev(T1, "Critical", "Room2SensorA", "Room2"),
                ev(T2, "Critical", "Room2SensorB", "Room2"),
                ev(T3, "Critical", "Room2SensorC", "Room2"));

        String html = section.build(events).html();

        List<Integer> numbers = new ArrayList<>();
        Matcher m = Pattern.compile("<td>(\\d+)\\.</td>").matcher(html);
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1)));
        }

        assertEquals(List.of(1, 2, 3, 4, 5), numbers,
                "нумерація має йти наскрізно 1..5 без перезапуску на межі Room1/Room2");
    }

    // --- клас "nw" на колонках Час/Стан ---

    @Test
    void build_timeAndStateColumns_haveNwClass() {
        String html = section.build(List.of(ev(T1, "Critical", "Sensor1", "Room1"))).html();

        assertTrue(html.contains("<th class=\"nw\">Час</th>"));
        assertTrue(html.contains("<th class=\"nw\">Стан</th>"));
        // Обидві td-комірки рядка (час + стан) також мають клас nw.
        long nwTdCount = Pattern.compile("<td class=\"nw\">").matcher(html).results().count();
        assertEquals(2, nwTdCount);
    }

    // --- HTML показує УСІ REPORTABLE_STATES, plainText -- лише CLAUDE_STATES ---

    @ParameterizedTest
    @ValueSource(strings = {"Critical", "High Critical", "Low Critical",
        "High Warning", "Low Warning", "Warning", "Sensor Error"})
    void build_html_includesEveryReportableState(String state) {
        String html = section.build(List.of(ev(T1, state, "Sensor1", "Room1"))).html();
        assertTrue(html.contains(">" + state + "<") || html.contains("<b>" + state + "</b>"),
                "HTML має відображати стан " + state);
    }

    @Test
    void build_plainText_includesOnlyClaudeStates_excludesLowAndSensorError() {
        List<RamosTrapEvent> events = List.of(
                ev(T1, "Critical", "CriticalSensor", "Room1"),
                ev(T2, "High Warning", "HighWarningSensor", "Room1"),
                ev(T3, "Low Critical", "LowCriticalSensor", "Room1"),
                ev(T4, "Sensor Error", "ErrorSensor", "Room1"));

        SectionResult result = section.build(events);

        assertTrue(result.plainText().contains("CriticalSensor"));
        assertTrue(result.plainText().contains("HighWarningSensor"));
        assertFalse(result.plainText().contains("LowCriticalSensor"));
        assertFalse(result.plainText().contains("ErrorSensor"));

        // а в html усі чотири є.
        assertTrue(result.html().contains("CriticalSensor"));
        assertTrue(result.html().contains("HighWarningSensor"));
        assertTrue(result.html().contains("LowCriticalSensor"));
        assertTrue(result.html().contains("ErrorSensor"));
    }

    @Test
    void build_plainText_emptyWhenNoClaudeStateEventsPresent_butHtmlNonEmpty() {
        List<RamosTrapEvent> events = List.of(
                ev(T1, "Low Critical", "LowCriticalSensor", "Room1"),
                ev(T2, "Sensor Error", "ErrorSensor", "Room1"));

        SectionResult result = section.build(events);

        assertEquals("", result.plainText());
        assertFalse(result.html().isBlank());
        assertFalse(result.isEmpty(), "isEmpty() зважає лише на html");
    }

    @Test
    void build_plainText_headerLine_exactText() {
        SectionResult result = section.build(List.of(ev(T1, "Critical", "Sensor1", "Room1")));
        assertTrue(result.plainText().startsWith("Ramos події, що потребують уваги:\n"));
    }

    // --- екранування спецсимволів ---

    @Test
    void build_sensorNameWithHtmlSpecialChars_isEscaped() {
        String html = section.build(List.of(
                ev(T1, "Critical", "A & B <script>", "Room1"))).html();
        assertTrue(html.contains("A &amp; B &lt;script&gt;"));
        assertFalse(html.contains("<script>"));
    }
}
