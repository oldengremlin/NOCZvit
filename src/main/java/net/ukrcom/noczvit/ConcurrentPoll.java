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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Bounded-concurrency fan-out on virtual threads, shared by every part of the report that polls
 * many independent keys (SNMP hosts, Zabbix API queries, ...) and wants a hard cap on how many
 * run at once without hand-rolling a semaphore each time.
 *
 * <p>Originally lived as a private method on {@code snmp.Client}; moved here once a second
 * caller ({@code zabbix.PowerResilienceAuditor}) needed the exact same shape — per the project
 * rule of extracting shared code rather than copying a private method across packages.
 */
@Slf4j
public final class ConcurrentPoll {

    private ConcurrentPoll() {
    }

    /**
     * Runs {@code query} for every key on its own virtual thread, bounded to
     * {@code maxConcurrent} in flight, and returns the successful results in input order.
     *
     * <p>Failed queries are logged and dropped rather than aborting the batch — one bad key
     * must not cost every other result. The semaphore and the result list are per-call locals,
     * so concurrent callers never share bounding state; results are collected on the calling
     * thread after the executor's try-with-resources has joined every task.
     *
     * @param keys          keys to query (hostnames, item IDs, incident records, ...)
     * @param query         per-key query; exceptions are caught and logged, not rethrown
     * @param maxConcurrent upper bound on simultaneously in-flight queries
     * @param logLabel      label used in the failure log line
     * @return successful results, in the order of {@code keys}
     */
    public static <K, T> List<T> run(List<K> keys, Function<K, T> query, int maxConcurrent, String logLabel) {
        Semaphore sem = new Semaphore(maxConcurrent);
        List<Future<T>> futures = new ArrayList<>(keys.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (K key : keys) {
                futures.add(executor.submit(() -> {
                    sem.acquire();
                    try {
                        return query.apply(key);
                    } finally {
                        sem.release();
                    }
                }));
            }
        }

        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("{} query failed: {}", logLabel, e.getCause().getMessage());
            }
        }
        return results;
    }
}
