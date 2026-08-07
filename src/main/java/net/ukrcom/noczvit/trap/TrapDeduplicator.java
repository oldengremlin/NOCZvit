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
 * Прибирає дубльовані події трапів, що виникають через повторну доставку приймачем SNMP-трапів.
 *
 * <p>Дедуплікуються лише трапи {@code Cold Start} (вони зазвичай спрацьовують по кілька разів
 * при перезавантаженні). Усі інші трапи проходять без змін. Дедуплікація групує за
 * {@code (hostname, trapType)} і залишає лише першу подію в межах часового вікна.
 */
@Slf4j
public class TrapDeduplicator {

    static final String COLD_START = "Cold Start";

    private TrapDeduplicator() {
    }

    /**
     * Дедуплікує події Cold Start у межах заданого часового вікна.
     *
     * @param events        вхідні події трапів (порядок не обов'язковий, але допомагає логуванню)
     * @param windowSeconds часове вікно в секундах; події з однаковим {@code (hostname, trapType)}
     *                      у межах цього вікна після першої відкидаються
     * @return дедуплікований список, відсортований за міткою часу за зростанням
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
