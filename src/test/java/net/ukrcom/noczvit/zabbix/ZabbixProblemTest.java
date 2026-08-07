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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Тести {@link ZabbixProblem}: акцесори record-а та точна умова {@link ZabbixProblem#isActive()}.
 */
class ZabbixProblemTest {

    @Test
    @DisplayName("isActive: rClock == 0 -> проблема активна (ще не вирішена)")
    void isActive_rClockZero_returnsTrue() {
        ZabbixProblem problem = new ZabbixProblem("r234-1", "Link down", 1_700_000_000L, 0L);

        assertTrue(problem.isActive());
    }

    @Test
    @DisplayName("isActive: rClock != 0 -> проблема вирішена (не активна)")
    void isActive_rClockNonZero_returnsFalse() {
        ZabbixProblem problem = new ZabbixProblem("r234-1", "Link down", 1_700_000_000L, 1_700_000_300L);

        assertFalse(problem.isActive());
    }

    @Test
    @DisplayName("isActive: точна умова rClock == 0, а не rClock <= 0 (від'ємне значення теж 'активне' не є)")
    void isActive_negativeRClock_treatedAsResolved() {
        // Zabbix API нормально не повертає від'ємний rClock, але перевіряємо точний код умови
        // (rClock == 0), а не якесь евристичне "rClock <= 0".
        ZabbixProblem problem = new ZabbixProblem("r234-1", "Link down", 1_700_000_000L, -1L);

        assertFalse(problem.isActive());
    }

    @Test
    @DisplayName("Акцесори record-а повертають значення, передані у конструктор")
    void accessors_returnConstructorValues() {
        ZabbixProblem problem = new ZabbixProblem("r234-1", "Link down", 1_700_000_000L, 1_700_000_300L);

        assertEquals("r234-1", problem.host());
        assertEquals("Link down", problem.name());
        assertEquals(1_700_000_000L, problem.clock());
        assertEquals(1_700_000_300L, problem.rClock());
    }
}
