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
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrapDeduplicatorTest {

    private static final Instant T0 = Instant.parse("2026-08-07T10:00:00Z");
    private static final int WINDOW = 30;

    private static TrapEvent ev(String hostname, String trapType, Instant ts) {
        return new TrapEvent(ts, "10.0.0.1", hostname, trapType, TrapEvent.CLASS_PDC);
    }

    @Test
    void nonColdStart_neverDeduplicated_evenIfIdenticalAndSimultaneous() {
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Active:Alarm:Loss of Mains", T0),
                ev("pdc-r1-1", "Active:Alarm:Loss of Mains", T0));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(2, result.size());
    }

    @Test
    void coldStart_duplicateWithinWindow_onlyFirstKept() {
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Cold Start", T0),
                ev("pdc-r1-1", "Cold Start", T0.plusSeconds(10)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(1, result.size());
        assertEquals(T0, result.get(0).timestamp());
    }

    @Test
    void coldStart_boundary_diffEqualsWindow_isStillDropped() {
        // ev.timestamp().isAfter(prev.plusSeconds(window)) — рівність не є "after", тож межа
        // вікна включно вважається дублікатом.
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Cold Start", T0),
                ev("pdc-r1-1", "Cold Start", T0.plusSeconds(WINDOW)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(1, result.size());
    }

    @Test
    void coldStart_boundary_diffWindowPlusOne_isKept() {
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Cold Start", T0),
                ev("pdc-r1-1", "Cold Start", T0.plusSeconds(WINDOW + 1)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(2, result.size());
    }

    @Test
    void coldStart_differentHostnames_doNotInterfere() {
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Cold Start", T0),
                ev("pdc-r2-1", "Cold Start", T0.plusSeconds(1)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(2, result.size());
    }

    @Test
    void coldStart_caseInsensitiveDetection_bothVariantsDeduplicateTogether() {
        // COLD_START.equalsIgnoreCase() вирішує, чи трап є Cold Start-ом взагалі — обидва варіанти
        // тут проходять у дедуп-гілку. Ключ групування теж нормалізований до нижнього регістру
        // (TrapDeduplicator.java, ev.trapType().toLowerCase()), тож "cold start" і "COLD START"
        // від одного хоста в одному вікні тепер дублюють одне одного, як і мало бути.
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "cold start", T0),
                ev("pdc-r1-1", "COLD START", T0.plusSeconds(1)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(1, result.size());
        assertEquals("cold start", result.get(0).trapType());
    }

    @Test
    void coldStart_sameCaseRepeatedWithinWindow_dropped() {
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "cold start", T0),
                ev("pdc-r1-1", "cold start", T0.plusSeconds(1)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(1, result.size());
    }

    @Test
    void result_isSortedByTimestampAscending_regardlessOfInputOrder() {
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Active:Alarm:High Temperature", T0.plusSeconds(50)),
                ev("pdc-r1-1", "Cleared:Alarm:High Temperature", T0));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(2, result.size());
        assertEquals(T0, result.get(0).timestamp());
        assertEquals(T0.plusSeconds(50), result.get(1).timestamp());
    }

    @Test
    void coldStart_slidingWindow_extendsFromLastKept_notLastSeen() {
        // Events at 0s, 15s, 30s, 31s with window=30.
        //  - 0s: kept (first), lastKept=0
        //  - 15s: 15 <= 30 from lastKept(0) -> dropped, lastKept STAYS 0 (not updated on drop)
        //  - 30s: 30 <= 30 from lastKept(0) -> dropped (boundary, still inclusive)
        //  - 31s: 31 > 30 from lastKept(0) -> kept, lastKept=31
        // Same "extend from last KEPT event" invariant as imap.Client.deduplicateAdlink.
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Cold Start", T0),
                ev("pdc-r1-1", "Cold Start", T0.plusSeconds(15)),
                ev("pdc-r1-1", "Cold Start", T0.plusSeconds(30)),
                ev("pdc-r1-1", "Cold Start", T0.plusSeconds(31)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        assertEquals(2, result.size());
        assertEquals(T0, result.get(0).timestamp());
        assertEquals(T0.plusSeconds(31), result.get(1).timestamp());
    }

    @Test
    void mixedColdStartAndOtherTraps_onlyColdStartAffected() {
        List<TrapEvent> input = List.of(
                ev("pdc-r1-1", "Cold Start", T0),
                ev("pdc-r1-1", "Cold Start", T0.plusSeconds(5)),
                ev("pdc-r1-1", "Active:Alarm:Loss of Mains", T0.plusSeconds(1)),
                ev("pdc-r1-1", "Active:Alarm:Loss of Mains", T0.plusSeconds(2)));

        List<TrapEvent> result = TrapDeduplicator.deduplicate(input, WINDOW);

        // 1 Cold Start survives (of 2) + both Loss of Mains events pass through untouched.
        assertEquals(3, result.size());
        long lossOfMainsCount = result.stream().filter(e -> e.trapType().equals("Active:Alarm:Loss of Mains")).count();
        assertEquals(2, lossOfMainsCount);
    }

    @Test
    void emptyInput_returnsEmptyList() {
        assertTrue(TrapDeduplicator.deduplicate(List.of(), WINDOW).isEmpty());
    }
}
