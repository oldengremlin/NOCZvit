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

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RamosTrapEventTest {

    // --- CLAUDE_STATES ---

    @Test
    void claudeStates_exactSet_onlyNonDirectionalAndRisingTemperature() {
        assertEquals(Set.of("Critical", "High Critical", "Warning", "High Warning"),
                RamosTrapEvent.CLAUDE_STATES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Low Critical", "Low Warning"})
    void claudeStates_fallingTemperatureStates_areExcluded(String state) {
        assertFalse(RamosTrapEvent.CLAUDE_STATES.contains(state));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Critical", "High Critical", "Warning", "High Warning"})
    void claudeStates_containsExpectedMembers(String state) {
        assertTrue(RamosTrapEvent.CLAUDE_STATES.contains(state));
    }

    // --- REPORTABLE_STATES ---

    @Test
    void reportableStates_exactSet() {
        assertEquals(Set.of(
                "Critical", "High Critical", "Low Critical",
                "High Warning", "Low Warning", "Warning",
                "Sensor Error"), RamosTrapEvent.REPORTABLE_STATES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Normal", "Connect", "Disconnect"})
    void reportableStates_normalOperationStates_areExcluded(String state) {
        assertFalse(RamosTrapEvent.REPORTABLE_STATES.contains(state));
    }

    @Test
    void reportableStates_isSupersetOfClaudeStates() {
        assertTrue(RamosTrapEvent.REPORTABLE_STATES.containsAll(RamosTrapEvent.CLAUDE_STATES));
    }

    @Test
    void reportableStates_sensorError_reportableButNotClaude() {
        assertTrue(RamosTrapEvent.REPORTABLE_STATES.contains("Sensor Error"));
        assertFalse(RamosTrapEvent.CLAUDE_STATES.contains("Sensor Error"));
    }
}
