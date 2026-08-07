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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.TestFixtures;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PdIncidentParserTest {

    private static final String DATE_STR = "Mon, 1 Jan 2025 08:00:00 +0200";
    private static final long UNIX_DATE = 1_735_714_800L;

    private static RawMessage msg(String subject) {
        return new RawMessage(DATE_STR, UNIX_DATE, subject, "", "");
    }

    private static PdIncidentParser parserWith(Path tempDir, Map<String, String> pd) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);
        return new PdIncidentParser(dictionary);
    }

    // --- isIgnored -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "[-] Problem: IVR-1: something happened",
        "[-] Problem: srv-TELEVIEV: something happened",
        "[-] Problem: Z-SQL-1: something happened",
        "[-] Problem: UVPN-1: something happened",
        "[-] Problem: SDH-OSM-1: something happened",
        "[-] Problem: astashov-1: something happened",
        "[-] Problem: console-1: something happened",
        "[-] Problem: m: NS1 down",
        "[-] Problem: d: NS down",
        "[-] Problem: ap1: pa2 has crashed",
    })
    void parse_ignoredSubjects_returnEmpty(String subject, @TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        assertTrue(parser.parse(msg(subject)).isEmpty());
    }

    @Test
    void parse_pairedHostPortException_alcaIsNotIgnored(@TempDir Path tempDir) throws Exception {
        // The paired host-port ignore regex explicitly excludes subjects containing "alca".
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: ap1: pa2 has crashed alca"));
        assertTrue(result.isPresent());
    }

    // --- device-suffix branch (from ends with ":") ----------------------------------------

    @Test
    void parse_icmpProblem_startStatusAndDoublePrefixStrip(@TempDir Path tempDir) throws Exception {
        // KNOWN QUIRK (pinning current behaviour, not fixing per task instructions):
        // PdIncidentParser strips the device prefix itself (DEVICE_PREFIX_PATTERN, "^[rsp]|ies\d?-|alca-")
        // *before* calling dictionary.resolvePD, and Dictionary.lookupPD (PdIncidentParser.java:72-75,
        // Dictionary.java:157-177) performs the *same* prefix/suffix stripping again internally. For a
        // hostname whose remainder after the first strip still starts with r/s/p (e.g. "ssks-2" -> "sks-2"),
        // the fallback path ends up returning the once-stripped "sks-2" rather than the untouched
        // "ssks-2" — an unintended double-normalisation when the PD dictionary has no matching entry.
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: ssks-2: Unavailable by ICMP ping"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals(Status.START, incident.status());
        assertEquals("sks-2", incident.location());
        assertEquals("ssks-2", incident.device());
        assertEquals(List.of("sks-2"), incident.reviewNames());
        assertEquals("Zabbix зареєстровано початок інциденту, зникнення зв'язку з обладнанням на sks-2",
                incident.description());
    }

    @Test
    void parse_icmpResolved_endStatus(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[+] Resolved: ssks-2: Unavailable by ICMP ping"));

        assertTrue(result.isPresent());
        assertEquals(Status.END, result.get().status());
        assertEquals("Zabbix зареєстровано кінець інциденту, зникнення зв'язку з обладнанням на sks-2",
                result.get().description());
    }

    @Test
    void parse_restartUnderProblemSubject_isInformationalNotIncident(@TempDir Path tempDir) throws Exception {
        // PD-only rule in resolveStatus: " Problem:" + type="been" => Status.NONE (a restart is
        // informational, not an incident start/end pair).
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: ssks-2: ssks-2 has been restarted"));

        assertTrue(result.isPresent());
        assertEquals(Status.NONE, result.get().status());
        assertEquals("Zabbix зареєстровано перезавантаження обладнання sks-2", result.get().description());
    }

    @Test
    void parse_resolvedRestart_isIgnored(@TempDir Path tempDir) throws Exception {
        // "Resolved" + type "been" is explicitly dropped before any device parsing happens.
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[+] Resolved: ssks-2: ssks-2 has been restarted"));

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_resolvedViaDictionary_noReviewNeeded(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of("^sks-2", "Тестова локація СКС"));
        Optional<Incident> result = parser.parse(msg("[-] Problem: ssks-2: Unavailable by ICMP ping"));

        assertTrue(result.isPresent());
        assertEquals("Тестова локація СКС", result.get().location());
        assertTrue(result.get().reviewNames().isEmpty());
    }

    @Test
    void parse_deviceWithoutNumericSuffix_defaultsTo65535(@TempDir Path tempDir) throws Exception {
        // "p234:" does not match ".*-\d+:$", so the colon is replaced by "-65535:" before the
        // device-prefix strip.
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: p234: Unavailable by ICMP ping"));

        assertTrue(result.isPresent());
        assertEquals("234-65535", result.get().location());
        assertEquals(List.of("234-65535"), result.get().reviewNames());
    }

    @Test
    void parse_iesPrefix_stripped(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: ies2-hoh15-3: Unavailable by ICMP ping"));

        assertTrue(result.isPresent());
        assertEquals("hoh15-3", result.get().location());
    }

    @Test
    void parse_alcaPrefix_stripped(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: alca-test-4: Unavailable by ICMP ping"));

        assertTrue(result.isPresent());
        assertEquals("test-4", result.get().location());
    }

    // --- no-colon branch (device without trailing colon, e.g. "has been restarted" plain) --

    @Test
    void parse_noColonSuffix_usesRawFromWithoutDictionaryLookup(@TempDir Path tempDir) throws Exception {
        // "sw1" (parts[2]) does not end with ":" -> falls into the trailing branch of parse():
        // no dictionary resolution happens, reviewNames stays empty, description uses the raw token.
        PdIncidentParser parser = parserWith(tempDir, Map.of("^sw1", "Some Other Location"));
        Optional<Incident> result = parser.parse(msg("[-] Problem: sw1 has stopped pinging device"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals(Status.START, incident.status());
        assertEquals("sw1", incident.location());
        assertEquals("sw1", incident.device());
        assertTrue(incident.reviewNames().isEmpty());
        assertEquals("Zabbix зареєстровано початок інциденту, pinging sw1", incident.description());
    }

    // --- buildDescription switch coverage (exercised via the no-colon branch to isolate the
    // event-type wording from dictionary/device-prefix concerns) -----------------------------

    @Test
    void parse_typeIcmp_wording(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: host1 aa bb ICMP cc"));
        assertEquals("Zabbix зареєстровано початок інциденту, зникнення зв'язку з обладнанням на host1",
                result.get().description());
    }

    @Test
    void parse_typeUnavailable_wording(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: host1 aa bb Unavailable cc"));
        assertEquals("Zabbix зареєстровано початок інциденту, зникнення підключення host1",
                result.get().description());
    }

    @Test
    void parse_typeBy_wording(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: host1 aa bb by cc"));
        assertEquals("Zabbix зареєстровано початок інциденту, зникнення підключення host1",
                result.get().description());
    }

    @Test
    void parse_typeBeenViaNoColonBranch_wording(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: host1 aa bb been cc"));
        assertEquals(Status.NONE, result.get().status());
        assertEquals("Zabbix зареєстровано перезавантаження обладнання host1", result.get().description());
    }

    @Test
    void parse_typeDefault_usesRawTypeToken(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: host1 aa bb SomethingElse cc"));
        assertEquals("Zabbix зареєстровано початок інциденту, SomethingElse host1", result.get().description());
    }

    @Test
    void parse_neitherProblemNorResolved_statusNone(@TempDir Path tempDir) throws Exception {
        PdIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[?] Notice: host1 aa bb ICMP cc"));
        assertFalse(result.isEmpty());
        assertEquals(Status.NONE, result.get().status());
    }
}
