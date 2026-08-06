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

/**
 * One host's power-resilience audit for a single outage: two honest snapshots (state of its
 * known interfaces at the moment the host itself fell, and again at the moment it recovered),
 * with no claim about anything in between — the host was unreachable via SNMP for that whole
 * window, so nothing about it was observable.
 *
 * @param host                short Zabbix hostname
 * @param location            human-readable location ({@link Dictionary#resolvePD}); may equal
 *                             {@code host} when unresolved
 * @param fallInstant         when the host itself became unreachable
 * @param recoveryInstant     when the host became reachable again
 * @param alreadyDownAtFall   interfaces already DOWN at the moment the host fell
 * @param stillUpAtFall       interfaces still UP at that moment — equals
 *                             {@code recoveredBeforeUs + stillDownAfterUs + noData}'s share of
 *                             this group; see {@link #totalKnown()}
 * @param recoveredBeforeUs   of {@code stillUpAtFall}, how many were already UP again by the
 *                             time the host recovered — i.e. beat the host back
 * @param stillDownAfterUs    of {@code stillUpAtFall}, how many were still DOWN when the host
 *                             recovered — i.e. worse off than the host
 * @param noData              interfaces where a snapshot could not be read at all (no history at
 *                             that instant); excluded from every ratio above, shown separately
 * @param alreadyDownNames    names of the {@code alreadyDownAtFall} interfaces
 * @param recoveredNames      names of the {@code recoveredBeforeUs} interfaces
 * @param stillDownNames      names of the {@code stillDownAfterUs} interfaces
 * @param uptimeBefore        {@code system.uptime} value at-or-before the fall, if available
 * @param uptimeAfter         {@code system.uptime} value at-or-after the recovery, if available
 * @param verdict             soft hint shown only at the two unambiguous edges (all/none of the
 *                             known interfaces fell first); empty in every other case — the
 *                             ambiguous middle gets facts, not a guess
 */
public record PowerResilienceResult(
        String host,
        String location,
        Instant fallInstant,
        Instant recoveryInstant,
        int alreadyDownAtFall,
        int stillUpAtFall,
        int recoveredBeforeUs,
        int stillDownAfterUs,
        int noData,
        List<String> alreadyDownNames,
        List<String> recoveredNames,
        List<String> stillDownNames,
        Optional<Long> uptimeBefore,
        Optional<Long> uptimeAfter,
        String verdict) {

    /** Interfaces with a usable state-at-fall snapshot — the denominator of the T_down ratio. */
    public int totalKnown() {
        return alreadyDownAtFall + stillUpAtFall;
    }

    /**
     * {@code true} when Zabbix recorded a lower {@code system.uptime} after recovery than before
     * the fall. Shown only as a raw fact (see {@link PowerResilienceAuditor}) — the counter can
     * wrap around on its own without a real reboot, so this is not treated as proof either way.
     */
    public boolean uptimeDecreased() {
        return uptimeBefore.isPresent() && uptimeAfter.isPresent()
                && uptimeAfter.get() < uptimeBefore.get();
    }
}
