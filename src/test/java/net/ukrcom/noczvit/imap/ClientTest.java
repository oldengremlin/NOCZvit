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
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Package-private test: exercises Client.deduplicateAdlink/isPdMessage/isOspfMessage/
// isAdlinkMessage/isOsmMessage directly, per the package-visibility widening documented in
// Client.java specifically for this purpose.
class ClientTest {

    private static final String ADLINK_SUBJECT = "[-] Problem: adlink-hoh15-1: card 0, port 0, line 0 - Fault";

    private static RawMessage msg(String subject, long unixDate) {
        return new RawMessage("Mon, 1 Jan 2025 08:00:00 +0200", unixDate, subject, "", "");
    }

    // --- deduplicateAdlink -----------------------------------------------------------------

    @Test
    void deduplicateAdlink_nonAdlinkMessagesAlwaysKept(@TempDir Path tempDir) throws Exception {
        Client client = clientIn(tempDir);
        RawMessage a = msg("[-] Problem: host1: Unavailable by ICMP ping", 1000L);
        RawMessage b = msg("[-] Problem: host1: Unavailable by ICMP ping", 1010L);

        List<RawMessage> result = client.deduplicateAdlink(List.of(a, b));

        assertEquals(2, result.size());
    }

    @Test
    void deduplicateAdlink_duplicateWithinWindow_isRemoved(@TempDir Path tempDir) throws Exception {
        Client client = clientIn(tempDir);
        RawMessage first = msg(ADLINK_SUBJECT, 1000L);
        RawMessage duplicate = msg(ADLINK_SUBJECT, 1030L);

        List<RawMessage> result = client.deduplicateAdlink(List.of(first, duplicate));

        assertEquals(List.of(first), result);
    }

    @Test
    void deduplicateAdlink_boundaryDiff60_isDuplicate(@TempDir Path tempDir) throws Exception {
        Client client = clientIn(tempDir);
        RawMessage first = msg(ADLINK_SUBJECT, 1000L);
        RawMessage atSixty = msg(ADLINK_SUBJECT, 1060L);

        List<RawMessage> result = client.deduplicateAdlink(List.of(first, atSixty));

        assertEquals(List.of(first), result);
    }

    @Test
    void deduplicateAdlink_boundaryDiff61_isKept(@TempDir Path tempDir) throws Exception {
        Client client = clientIn(tempDir);
        RawMessage first = msg(ADLINK_SUBJECT, 1000L);
        RawMessage atSixtyOne = msg(ADLINK_SUBJECT, 1061L);

        List<RawMessage> result = client.deduplicateAdlink(List.of(first, atSixtyOne));

        assertEquals(List.of(first, atSixtyOne), result);
    }

    @Test
    void deduplicateAdlink_windowMeasuredFromLastKept_notFirstSeen(@TempDir Path tempDir) throws Exception {
        // t=0 kept; t=50 within 60s of the last KEPT (t=0) -> dropped; t=100 is 100s after the
        // last KEPT (still t=0, since t=50 never became "kept") -> more than 60s away -> kept.
        // A "distance from last seen" implementation would instead drop t=100 (only 50s after t=50).
        Client client = clientIn(tempDir);
        RawMessage t0 = msg(ADLINK_SUBJECT, 0L);
        RawMessage t50 = msg(ADLINK_SUBJECT, 50L);
        RawMessage t100 = msg(ADLINK_SUBJECT, 100L);

        List<RawMessage> result = client.deduplicateAdlink(List.of(t0, t50, t100));

        assertEquals(List.of(t0, t100), result);
    }

    @Test
    void deduplicateAdlink_unsortedInput_isSortedByTimestamp(@TempDir Path tempDir) throws Exception {
        Client client = clientIn(tempDir);
        RawMessage later = msg("[-] Problem: host1: Unavailable by ICMP ping", 2000L);
        RawMessage earlier = msg("[-] Problem: host2: Unavailable by ICMP ping", 1000L);

        List<RawMessage> result = client.deduplicateAdlink(List.of(later, earlier));

        assertEquals(List.of(earlier, later), result);
    }

    // --- isPdMessage -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "[-] Problem: host1: Unavailable by ICMP ping",
        "[-] Problem: host1: host1 has been restarted",
    })
    void isPdMessage_positive(String subject, @TempDir Path tempDir) throws Exception {
        assertTrue(clientIn(tempDir).isPdMessage(subject));
    }

    @Test
    void isPdMessage_negative(@TempDir Path tempDir) throws Exception {
        assertFalse(clientIn(tempDir).isPdMessage("[-] Problem: host1: eth0 ospfNbrStateChange"));
    }

    // --- isOspfMessage -----------------------------------------------------------------------

    @Test
    void isOspfMessage_positive(@TempDir Path tempDir) throws Exception {
        assertTrue(clientIn(tempDir).isOspfMessage("[-] Problem: r1: r1 eth0 ospfNbrStateChange"));
    }

    @Test
    void isOspfMessage_negative(@TempDir Path tempDir) throws Exception {
        assertFalse(clientIn(tempDir).isOspfMessage("[-] Problem: host1: Unavailable by ICMP ping"));
    }

    // --- isAdlinkMessage ---------------------------------------------------------------------

    @Test
    void isAdlinkMessage_positive(@TempDir Path tempDir) throws Exception {
        assertTrue(clientIn(tempDir).isAdlinkMessage(ADLINK_SUBJECT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[-] Problem: adlink-hoh15-1: card 0, port 0, line 0 - Something Else",
        "[-] Problem: host1: card 0, port 0, line 0 - Fault",
    })
    void isAdlinkMessage_negative_requiresBothSubstrings(String subject, @TempDir Path tempDir) throws Exception {
        assertFalse(clientIn(tempDir).isAdlinkMessage(subject));
    }

    // --- isOsmMessage ------------------------------------------------------------------------

    @Test
    void isOsmMessage_power_matchesInBothModes(@TempDir Path tempDir) throws Exception {
        assertTrue(clientIn(tempDir).isOsmMessage("Trap: Power Fail on device X"));
        assertTrue(clientIn(tempDir, true).isOsmMessage("Trap: Power Fail on device X"));
    }

    @Test
    void isOsmMessage_stm4_matchesInBothModes(@TempDir Path tempDir) throws Exception {
        assertTrue(clientIn(tempDir).isOsmMessage("STM STM-4 LOS alarm"));
        assertTrue(clientIn(tempDir, true).isOsmMessage("STM STM-4 LOS alarm"));
    }

    @Test
    void isOsmMessage_stm1_onlyMatchesInDebugMode(@TempDir Path tempDir) throws Exception {
        // config.isDebug() widens the allowed STM range from "2-9" to "1-9" — this is the crux
        // difference the task asked to cover.
        assertFalse(clientIn(tempDir).isOsmMessage("STM STM-1 LOS alarm"));
        assertTrue(clientIn(tempDir, true).isOsmMessage("STM STM-1 LOS alarm"));
    }

    @Test
    void isOsmMessage_unrelatedSubject_neverMatches(@TempDir Path tempDir) throws Exception {
        assertFalse(clientIn(tempDir).isOsmMessage("Some unrelated subject"));
        assertFalse(clientIn(tempDir, true).isOsmMessage("Some unrelated subject"));
    }

    // --- helpers -------------------------------------------------------------------------------

    private static Client clientIn(Path tempDir) throws Exception {
        return clientIn(tempDir, false);
    }

    private static Client clientIn(Path tempDir, boolean debug) throws Exception {
        Config config = debug ? TestFixtures.config("--debug") : TestFixtures.config();
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of());
        return new Client(config, dictionary);
    }
}
