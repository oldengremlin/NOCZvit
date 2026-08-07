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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.TestFixtures;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdlinkIncidentParserTest {

    private static final String DATE_STR = "Mon, 1 Jan 2025 08:00:00 +0200";
    private static final long UNIX_DATE = 1_735_714_800L;

    private static RawMessage msg(String subject) {
        return new RawMessage(DATE_STR, UNIX_DATE, subject, "", "");
    }

    private static AdlinkIncidentParser parserWith(Path tempDir, Map<String, String> pd) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);
        return new AdlinkIncidentParser(dictionary);
    }

    @Test
    void parse_lineKeyMatchesDictionary_resolvesEventAndLocation(@TempDir Path tempDir) throws Exception {
        // Dictionary.lineKey(device, card, port, line) builds "device:card:port:line" —
        // verify the AdlinkIncidentParser assembles exactly that composite key.
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of(
                "^adlink-hoh15-1:0:0:0$", "зникнення живлення на кондиціонери (лінія 0)",
                "^adlink-hoh15-1$", "Г.Джонса 15"));
        Optional<Incident> result = parser.parse(
                msg("[-] Problem: adlink-hoh15-1: card 0, port 0, line 0 - Fault"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals(Status.START, incident.status());
        assertEquals("Г.Джонса 15", incident.location());
        assertEquals("", incident.device());
        assertTrue(incident.reviewNames().isEmpty());
        assertEquals("Zabbix зареєстровано початок інциденту, зникнення живлення на кондиціонери (лінія 0)",
                incident.description());
    }

    @Test
    void parse_resolved_endStatus(@TempDir Path tempDir) throws Exception {
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of(
                "^adlink-hoh15-1:0:0:0$", "зникнення живлення на кондиціонери (лінія 0)",
                "^adlink-hoh15-1$", "Г.Джонса 15"));
        Optional<Incident> result = parser.parse(
                msg("[+] Resolved: adlink-hoh15-1: card 0, port 0, line 0 - Fault"));

        assertTrue(result.isPresent());
        assertEquals(Status.END, result.get().status());
    }

    @Test
    void parse_unmatchedSubject_returnsEmpty(@TempDir Path tempDir) throws Exception {
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("no adlink pattern here at all"));

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_unresolvedDeviceAndLine_fallsBackToGenericWording(@TempDir Path tempDir) throws Exception {
        // No dictionary entry at all (not even a bare device entry) -> genuine "unknown key"
        // fallback described in the class Javadoc: generic wording + both keys queued for review.
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(
                msg("[-] Problem: adlink-hoh15-1: card 9, port 9, line 9 - Fault"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals("adlink-hoh15-1", incident.location());
        assertEquals(List.of("adlink-hoh15-1", "adlink-hoh15-1:9:9:9"), incident.reviewNames());
        assertEquals("Zabbix зареєстровано початок інциденту, спрацювання сухого контакту, лінія 9",
                incident.description());
    }

    @Test
    void parse_bareDeviceEntryLeaksIntoUnmappedLine_documentedQuirk(@TempDir Path tempDir) throws Exception {
        // KNOWN QUIRK (pinning current behaviour, not fixing per task instructions):
        // Dictionary.firstMatch uses unanchored Matcher.find() (Dictionary.java:266-273), so a bare
        // device entry like "^adlink-hoh15-1=..." (no card/port/line suffix, exactly as shown in
        // AdlinkIncidentParser's own class Javadoc example) matches as a *prefix* of ANY composite
        // lineKey for that device ("adlink-hoh15-1:9:9:9" included) because the pattern has no "$"
        // anchor. An unmapped card/port/line therefore silently reuses the device's location text as
        // the event description instead of the generic "спрацювання сухого контакту, лінія N" wording
        // promised by the class Javadoc, and is NOT added to reviewNames even though the specific line
        // was never actually configured. Contrast with parse_unresolvedDeviceAndLine_fallsBackToGenericWording
        // above, which only gets the documented fallback because no bare device entry exists there.
        // Note: no trailing "$" on the keys here (matching the class Javadoc's own example
        // verbatim) — that is exactly what lets the bare device pattern prefix-match the
        // composite lineKey below; anchoring with "$" (as the other tests in this file do) would
        // prevent the leak.
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of(
                "^adlink-hoh15-1:0:0:0", "зникнення живлення на кондиціонери (лінія 0)",
                "^adlink-hoh15-1", "Г.Джонса 15"));
        Optional<Incident> result = parser.parse(
                msg("[-] Problem: adlink-hoh15-1: card 9, port 9, line 9 - Fault"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals("Г.Джонса 15", incident.location());
        assertTrue(incident.reviewNames().isEmpty());
        assertEquals("Zabbix зареєстровано початок інциденту, Г.Джонса 15", incident.description());
    }

    @Test
    void parse_trapPrefixIsOptional(@TempDir Path tempDir) throws Exception {
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of(
                "^adlink-hoh15-1:1:2:3$", "зникнення живлення на щось конкретне"));
        Optional<Incident> result = parser.parse(
                msg("[-] Problem: adlink-hoh15-1: Trap card 1, port 2, line 3 - Fault"));

        assertTrue(result.isPresent());
        assertEquals("зникнення живлення на щось конкретне", result.get().description()
                .replace("Zabbix зареєстровано початок інциденту, ", ""));
    }

    @Test
    void parse_multiDigitCardPortLine(@TempDir Path tempDir) throws Exception {
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of(
                "^adlink-hoh15-1:12:34:56$", "довгий номер лінії",
                "^adlink-hoh15-1$", "Г.Джонса 15"));
        Optional<Incident> result = parser.parse(
                msg("[-] Problem: adlink-hoh15-1: card 12, port 34, line 56 - Fault"));

        assertTrue(result.isPresent());
        assertTrue(result.get().description().endsWith("довгий номер лінії"));
        assertTrue(result.get().reviewNames().isEmpty());
    }

    @Test
    void parse_caseInsensitiveAdlinkPrefix_deviceKeepsOriginalCase(@TempDir Path tempDir) throws Exception {
        // ADLINK_PATTERN is compiled with (?i), so "ADLINK-hoh15-1" matches too; the captured
        // device group preserves whatever case the sender used.
        AdlinkIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(
                msg("[-] Problem: ADLINK-hoh15-1: card 0, port 0, line 0 - Fault"));

        assertTrue(result.isPresent());
        assertEquals("ADLINK-hoh15-1", result.get().location());
    }
}
