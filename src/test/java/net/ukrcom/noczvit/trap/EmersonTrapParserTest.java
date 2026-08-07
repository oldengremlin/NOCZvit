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
import java.time.LocalDateTime;
import java.util.List;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.imap.RawMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmersonTrapParserTest {

    private static final long REF_UNIX_DATE = 1754568000L; // 2025-08-07T12:00:00Z (used as email Date: epoch)

    private static String subject(String hostname) {
        return "Got trap from " + hostname + " - Trap Registrator <trap@ukrcom.net> - Fri Aug 07 12:00:00 2026";
    }

    private static String body(String tsStr, String ip, String rawTrapType) {
        return "At " + tsStr + ", from " + ip + ", after uptime 1:02:03:04.05, registered trap:\r\n\t" + rawTrapType;
    }

    private static RawMessage msg(String hostname, String tsStr, String ip, String rawTrapType) {
        return new RawMessage("ignored", REF_UNIX_DATE, subject(hostname), body(tsStr, ip, rawTrapType), "");
    }

    // --- happy path ---

    @Test
    void parse_pdcLossOfMains_producesFullyPopulatedEvent() {
        RawMessage rm = msg("pdc-r1-1", "07-08-2026 12:00:00", "10.0.0.5", "\"Active:Alarm:Loss of Mains\"");
        List<TrapEvent> events = EmersonTrapParser.parse(List.of(rm));

        assertEquals(1, events.size());
        TrapEvent ev = events.get(0);
        assertEquals("pdc-r1-1", ev.hostname());
        assertEquals("10.0.0.5", ev.ip());
        assertEquals(TrapEvent.CLASS_PDC, ev.deviceClass());
        assertEquals("Active:Alarm:Loss of Mains", ev.trapType());
        Instant expected = DateUtils.toInstant(LocalDateTime.of(2026, 8, 7, 12, 0, 0), REF_UNIX_DATE);
        assertEquals(expected, ev.timestamp());
    }

    @Test
    void parse_adcColdStart_classifiedAsAdc() {
        RawMessage rm = msg("adc-r2-3", "07-08-2026 09:15:30", "10.0.0.9", "\"Cold Start\"");
        List<TrapEvent> events = EmersonTrapParser.parse(List.of(rm));

        assertEquals(1, events.size());
        assertEquals(TrapEvent.CLASS_ADC, events.get(0).deviceClass());
        assertEquals("Cold Start", events.get(0).trapType());
    }

    @Test
    void parse_hostnameFromSubject_isLowercased() {
        RawMessage rm = msg("PDC-R1-1", "07-08-2026 12:00:00", "10.0.0.5", "\"Active:Alarm:Loss of Mains\"");
        List<TrapEvent> events = EmersonTrapParser.parse(List.of(rm));

        assertEquals("pdc-r1-1", events.get(0).hostname());
    }

    // --- device class classification / skip cases ---

    @Test
    void parse_unknownHostnamePrefix_isSkipped() {
        RawMessage rm = msg("switch-core-1", "07-08-2026 12:00:00", "10.0.0.5", "\"Active:Alarm:Loss of Mains\"");
        assertTrue(EmersonTrapParser.parse(List.of(rm)).isEmpty());
    }

    @Test
    void parse_malformedSubject_isSkipped() {
        RawMessage rm = new RawMessage("ignored", REF_UNIX_DATE,
                "SNMP trap notification", body("07-08-2026 12:00:00", "10.0.0.5", "\"Cold Start\""), "");
        assertTrue(EmersonTrapParser.parse(List.of(rm)).isEmpty());
    }

    @Test
    void parse_malformedBody_isSkipped() {
        RawMessage rm = new RawMessage("ignored", REF_UNIX_DATE, subject("pdc-r1-1"),
                "This is not the expected trap body format at all.", "");
        assertTrue(EmersonTrapParser.parse(List.of(rm)).isEmpty());
    }

    @Test
    void parse_emptyBody_isSkipped() {
        RawMessage rm = new RawMessage("ignored", REF_UNIX_DATE, subject("pdc-r1-1"), "", "");
        assertTrue(EmersonTrapParser.parse(List.of(rm)).isEmpty());
    }

    @Test
    void parse_blankBody_isSkipped() {
        RawMessage rm = new RawMessage("ignored", REF_UNIX_DATE, subject("pdc-r1-1"), "   \n  ", "");
        assertTrue(EmersonTrapParser.parse(List.of(rm)).isEmpty());
    }

    @Test
    void parse_nullBody_isSkipped() {
        RawMessage rm = new RawMessage("ignored", REF_UNIX_DATE, subject("pdc-r1-1"), null, "");
        assertTrue(EmersonTrapParser.parse(List.of(rm)).isEmpty());
    }

    // --- timestamp fallback: body carries an unparsable calendar date (e.g. month 13) ---

    @Test
    void parse_unparsableBodyTimestamp_fallsBackToEmailDate() {
        // "07-13-2026" matches the \d{2}-\d{2}-\d{4} shape, but month 13 is not a real calendar
        // value (the formatter's SMART resolver can clamp an out-of-range day-of-month, but not
        // fabricate a 13th month), so LocalDateTime.parse throws and EmersonTrapParser falls back
        // to msg.unixDate().
        RawMessage rm = msg("pdc-r1-1", "07-13-2026 12:00:00", "10.0.0.5", "\"Active:Alarm:Loss of Mains\"");
        List<TrapEvent> events = EmersonTrapParser.parse(List.of(rm));

        assertEquals(1, events.size());
        assertEquals(Instant.ofEpochSecond(REF_UNIX_DATE), events.get(0).timestamp());
    }

    // --- normalizeTrapType (quote stripping + ": " collapsing) ---

    @Test
    void normalizeTrapType_stripsSurroundingQuotesAndCollapsesColonSpace() {
        assertEquals("Active:Alarm:Battery Discharging",
                EmersonTrapParser.normalizeTrapType("\"Active:Alarm:   Battery Discharging\""));
    }

    @Test
    void normalizeTrapType_noQuotes_unchangedApartFromColonCollapse() {
        assertEquals("Active:Alarm:Loss of Mains",
                EmersonTrapParser.normalizeTrapType("Active:Alarm: Loss of Mains"));
    }

    @Test
    void normalizeTrapType_plainWordWithoutColon_isUntouched() {
        assertEquals("Cold Start", EmersonTrapParser.normalizeTrapType("\"Cold Start\""));
    }

    @Test
    void normalizeTrapType_onlyOpeningQuote_notStripped() {
        // Не закінчується лапкою — умова startsWith && endsWith не виконується, лапка лишається.
        assertEquals("\"Cold Start", EmersonTrapParser.normalizeTrapType("\"Cold Start"));
    }

    @Test
    void normalizeTrapType_singleQuoteCharacter_lengthGuardPreventsStrip() {
        // s.length() >= 2 guard: одинарний символ лапки не обрізається (інакше вийшов би порожній рядок).
        assertEquals("\"", EmersonTrapParser.normalizeTrapType("\""));
    }

    // --- list overload filters invalid messages while preserving order of the valid ones ---

    @Test
    void parse_listOverload_skipsInvalidAndPreservesOrderOfValid() {
        RawMessage valid1 = msg("pdc-r1-1", "07-08-2026 12:00:00", "10.0.0.5", "\"Active:Alarm:Loss of Mains\"");
        RawMessage badHost = msg("router-1", "07-08-2026 12:01:00", "10.0.0.6", "\"Cold Start\"");
        RawMessage valid2 = msg("adc-r1-2", "07-08-2026 12:02:00", "10.0.0.7", "\"Active:Alarm:Compressor Short Cycle\"");
        RawMessage badSubject = new RawMessage("ignored", REF_UNIX_DATE, "not a trap subject",
                body("07-08-2026 12:03:00", "10.0.0.8", "\"Cold Start\""), "");

        List<TrapEvent> events = EmersonTrapParser.parse(List.of(valid1, badHost, valid2, badSubject));

        assertEquals(2, events.size());
        assertEquals("pdc-r1-1", events.get(0).hostname());
        assertEquals("adc-r1-2", events.get(1).hostname());
    }

    @Test
    void parse_emptyInputList_returnsEmptyList() {
        assertTrue(EmersonTrapParser.parse(List.of()).isEmpty());
    }
}
