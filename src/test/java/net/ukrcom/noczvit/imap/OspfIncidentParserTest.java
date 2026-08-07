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

class OspfIncidentParserTest {

    private static final String DATE_STR = "Mon, 1 Jan 2025 08:00:00 +0200";
    private static final long UNIX_DATE = 1_735_714_800L;

    private static RawMessage msg(String subject) {
        return new RawMessage(DATE_STR, UNIX_DATE, subject, "", "");
    }

    private static OspfIncidentParser parserWith(Path tempDir, Map<String, String> pd) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);
        return new OspfIncidentParser(dictionary);
    }

    // Subject shape: "[±] Problem/Resolved: <host>: <router> <channel> ospfNbrStateChange" —
    // parts[3] is the router, parts[4] the channel; both are resolved independently through the
    // PD dictionary, unlike PdIncidentParser there is no device-prefix stripping here.

    @Test
    void parse_problem_startStatus_bothUnresolved_reviewNamesListBoth(@TempDir Path tempDir) throws Exception {
        OspfIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: r234-1: r234-1 eth0 ospfNbrStateChange"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals(Status.START, incident.status());
        assertEquals("r234-1", incident.location());
        assertEquals("r234-1", incident.device());
        assertEquals(List.of("r234-1", "eth0"), incident.reviewNames());
        assertEquals("Zabbix зареєстровано початок інциденту, падіння каналу на r234-1 по каналу eth0",
                incident.description());
    }

    @Test
    void parse_resolved_endStatus(@TempDir Path tempDir) throws Exception {
        OspfIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[+] Resolved: r234-1: r234-1 eth0 ospfNbrStateChange"));

        assertTrue(result.isPresent());
        assertEquals(Status.END, result.get().status());
        assertEquals("Zabbix зареєстровано кінець інциденту, падіння каналу на r234-1 по каналу eth0",
                result.get().description());
    }

    @Test
    void parse_routerResolved_channelUnresolved_reviewNamesOnlyChannel(@TempDir Path tempDir) throws Exception {
        OspfIncidentParser parser = parserWith(tempDir, Map.of("^r234-1$", "Прахових 50"));
        Optional<Incident> result = parser.parse(msg("[-] Problem: r234-1: r234-1 eth0 ospfNbrStateChange"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals("Прахових 50", incident.location());
        assertEquals(List.of("eth0"), incident.reviewNames());
        assertEquals("Zabbix зареєстровано початок інциденту, падіння каналу на Прахових 50 по каналу eth0",
                incident.description());
    }

    @Test
    void parse_bothResolved_reviewNamesEmpty(@TempDir Path tempDir) throws Exception {
        OspfIncidentParser parser = parserWith(tempDir, Map.of(
                "^r234-1$", "Прахових 50",
                "^eth0$", "Основний канал"));
        Optional<Incident> result = parser.parse(msg("[-] Problem: r234-1: r234-1 eth0 ospfNbrStateChange"));

        assertTrue(result.isPresent());
        assertTrue(result.get().reviewNames().isEmpty());
    }

    @Test
    void parse_shortSubject_missingRouterAndChannel_defaultToEmptyStrings(@TempDir Path tempDir) throws Exception {
        // Pinning current behaviour: parts.length checks fall back to "" for both router and
        // channel when the subject is truncated, which still resolves (unresolved -> "") and adds
        // the empty string twice to reviewNames rather than skipping review entirely.
        OspfIncidentParser parser = parserWith(tempDir, Map.of());
        Optional<Incident> result = parser.parse(msg("[-] Problem: r234-1:"));

        assertTrue(result.isPresent());
        Incident incident = result.get();
        assertEquals(Status.START, incident.status());
        assertEquals("", incident.location());
        assertEquals(List.of("", ""), incident.reviewNames());
        assertEquals("Zabbix зареєстровано початок інциденту, падіння каналу на по каналу ",
                incident.description());
    }
}
