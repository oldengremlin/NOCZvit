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
 * Обмежений паралельний fan-out на віртуальних потоках — спільний для всіх частин звіту, що
 * опитують багато незалежних ключів (SNMP-хости, Zabbix API-запити, ...) і потребують жорсткого
 * обмеження на кількість одночасних викликів без ручного `Semaphore` щоразу.
 *
 * <p>Раніше був приватним методом у {@code snmp.Client}; винесений сюди, коли той самий
 * механізм знадобився другому викликачу ({@code zabbix.PowerResilienceAuditor}) — за правилом
 * проєкту виносити спільний код у спільне місце, а не копіювати приватний метод між пакетами.
 */
@Slf4j
public final class ConcurrentPoll {

    private ConcurrentPoll() {
    }

    /**
     * Виконує {@code query} для кожного ключа на власному віртуальному потоці, обмежено
     * {@code maxConcurrent} одночасних викликів, і повертає успішні результати в порядку вхідних
     * ключів.
     *
     * <p>Невдалі запити логуються й відкидаються, а не переривають весь пакет — один поганий
     * ключ не повинен коштувати всіх інших результатів. Semaphore і список результатів —
     * локальні для кожного виклику, тож паралельні виклики ніколи не ділять стан обмеження;
     * результати збираються в потоці виклику вже після того, як executor у try-with-resources
     * дочекався завершення всіх задач.
     *
     * @param keys          ключі для опитування (hostname, item ID, записи інцидентів, ...)
     * @param query         запит на кожен ключ; винятки перехоплюються й логуються, не прокидаються далі
     * @param maxConcurrent верхня межа кількості одночасних запитів
     * @param logLabel      мітка для рядка логу про помилку
     * @return успішні результати, у порядку {@code keys}
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
