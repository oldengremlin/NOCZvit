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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes duplicate trap events that arise from SNMP trap receiver re-delivery.
 *
 * <p>Only {@code Cold Start} traps are deduplicated (they commonly fire multiple times on reboot).
 * All other traps pass through unchanged. Deduplication groups by
 * {@code (hostname, trapType)} and keeps only the first event within a time window.
 */
@Slf4j
public class TrapDeduplicator {

    static final String COLD_START = "Cold Start";

    private TrapDeduplicator() {
    }

    /**
     * Deduplicates Cold Start events within the given time window.
     *
     * @param events        input trap events (order is not required but helps logging)
     * @param windowSeconds time window in seconds; events of the same
     *                      {@code (hostname, trapType)} within this window after the first are dropped
     * @return deduplicated list, sorted by timestamp ascending
     */
    public static List<TrapEvent> deduplicate(List<TrapEvent> events, int windowSeconds) {
        List<TrapEvent> sorted = events.stream()
                .sorted(Comparator.comparing(TrapEvent::timestamp))
                .toList();

        Map<String, Instant> lastSeen = new HashMap<>();
        List<TrapEvent> result = new ArrayList<>();

        for (TrapEvent ev : sorted) {
            if (!COLD_START.equalsIgnoreCase(ev.trapType())) {
                result.add(ev);
                continue;
            }
            // trapType нормалізуємо до нижнього регістру: перевірка "це Cold Start?" вище
            // регістронезалежна (equalsIgnoreCase), а ключ групування мав лишатись
            // чутливим — "Cold Start" і "COLD START" від одного хоста в одному вікні
            // не бачили одне одного й дедуплікувались кожен сам із собою.
            String key = ev.hostname() + "|" + ev.trapType().toLowerCase();
            Instant prev = lastSeen.get(key);
            if (prev == null || ev.timestamp().isAfter(prev.plusSeconds(windowSeconds))) {
                lastSeen.put(key, ev.timestamp());
                result.add(ev);
            } else {
                log.debug("TrapDeduplicator: dropped duplicate Cold Start for {} at {}",
                        ev.hostname(), ev.timestamp());
            }
        }
        return result;
    }
}
