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
package net.ukrcom.noczvit;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentPollTest {

    @Test
    void run_preservesKeyOrder_evenWhenTasksCompleteOutOfOrder() {
        List<Integer> keys = List.of(1, 2, 3, 4, 5);
        // Ключ 1 «висить» найдовше, ключ 5 повертається майже миттєво — за завершенням
        // порядок був би [5,4,3,2,1], тож тест реально відрізняє збереження порядку keys
        // від збирання результатів у порядку завершення.
        Function<Integer, Integer> query = k -> {
            try {
                Thread.sleep((5 - k) * 40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return k * 10;
        };

        List<Integer> result = ConcurrentPoll.run(keys, query, 5, "order-test");

        assertEquals(List.of(10, 20, 30, 40, 50), result);
    }

    @Test
    void run_exceptionForOneKey_isSkipped_othersStillReturned() {
        List<Integer> keys = List.of(1, 2, 3);
        Function<Integer, Integer> query = k -> {
            if (k == 2) {
                throw new RuntimeException("boom");
            }
            return k * 100;
        };

        List<Integer> result = ConcurrentPoll.run(keys, query, 3, "error-test");

        assertEquals(List.of(100, 300), result);
    }

    @Test
    void run_neverExceedsMaxConcurrent() {
        AtomicInteger current = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        List<Integer> keys = IntStream.rangeClosed(1, 20).boxed().toList();
        int maxConcurrent = 3;

        Function<Integer, Integer> query = k -> {
            int inFlight = current.incrementAndGet();
            maxObserved.updateAndGet(prev -> Math.max(prev, inFlight));
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                current.decrementAndGet();
            }
            return k;
        };

        List<Integer> result = ConcurrentPoll.run(keys, query, maxConcurrent, "limit-test");

        assertEquals(20, result.size());
        assertTrue(maxObserved.get() <= maxConcurrent,
                "Спостережено " + maxObserved.get() + " одночасних викликів, дозволено " + maxConcurrent);
        assertTrue(maxObserved.get() >= 2, "Очікували реальний паралелізм, а не послідовне виконання");
    }

    @Test
    void run_emptyKeys_returnsEmptyResult() {
        List<Integer> result = ConcurrentPoll.<Integer, Integer>run(List.of(), k -> k, 5, "empty-test");

        assertTrue(result.isEmpty());
    }
}
