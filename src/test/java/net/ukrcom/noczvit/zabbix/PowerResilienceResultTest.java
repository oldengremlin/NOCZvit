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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerResilienceResultTest {

    private static PowerResilienceResult withUptime(Optional<Long> before, Optional<Long> after) {
        return new PowerResilienceResult(
                "host1", "Локація 1",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(),
                before, after, Optional.empty(), "");
    }

    @Test
    void totalKnown_sumsAlreadyDownAndStillUp() {
        PowerResilienceResult r = new PowerResilienceResult(
                "host1", "Локація 1",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                3, 5, 2, 2, 1, 1, 0,
                List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), "");

        assertEquals(8, r.totalKnown());
    }

    @Test
    void totalKnown_ignoresNoDataAtFallAndIgnoredPorts() {
        // noDataAtFall та ignoredPorts НЕ входять у totalKnown() — лише alreadyDownAtFall + stillUpAtFall.
        PowerResilienceResult r = new PowerResilienceResult(
                "host1", "Локація 1",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                1, 1, 1, 0, 100, 0, 100,
                List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), "");

        assertEquals(2, r.totalKnown());
    }

    @Test
    void uptimeDecreased_trueWhenAfterLessThanBefore() {
        PowerResilienceResult r = withUptime(Optional.of(500L), Optional.of(100L));

        assertTrue(r.uptimeDecreased());
    }

    @Test
    void uptimeDecreased_falseWhenAfterGreaterOrEqual() {
        assertFalse(withUptime(Optional.of(500L), Optional.of(500L)).uptimeDecreased());
        assertFalse(withUptime(Optional.of(500L), Optional.of(900L)).uptimeDecreased());
    }

    @Test
    void uptimeDecreased_falseWhenEitherSideMissing() {
        assertFalse(withUptime(Optional.empty(), Optional.of(100L)).uptimeDecreased());
        assertFalse(withUptime(Optional.of(500L), Optional.empty()).uptimeDecreased());
        assertFalse(withUptime(Optional.empty(), Optional.empty()).uptimeDecreased());
    }

    @Test
    void interfaceObservation_holdsNameAndTimestamp() {
        Instant observedAt = Instant.ofEpochSecond(12345);
        PowerResilienceResult.InterfaceObservation obs =
                new PowerResilienceResult.InterfaceObservation("Interface 5(uplink)", observedAt);

        assertEquals("Interface 5(uplink)", obs.name());
        assertEquals(observedAt, obs.observedAt());
    }
}
