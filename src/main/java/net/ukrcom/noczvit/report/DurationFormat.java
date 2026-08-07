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
package net.ukrcom.noczvit.report;

import java.time.Duration;
import java.time.Instant;

/**
 * Єдине формулювання для колонки «Тривалість», спільне для всіх таблиць звіту.
 *
 * <p>Проміжки коротші за хвилину виводяться як {@code "< 1 хв"} замість кількості секунд: звіт
 * пишеться для передачі зміни, де «майже миттєво» — корисний факт, а точна кількість секунд —
 * шум. Це також уникає безглуздого {@code "0 с"}, яке давали миттєві події (Cold Start,
 * Compressor Short Cycle) — вони закриваються у власну мітку часу й не мають тривалості взагалі.
 *
 * <p><b>Потокобезпека:</b> без стану — статичні методи оперують лише своїми аргументами.
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    /**
     * Форматує тривалість у секундах: {@code "< 1 хв"} до хвилини, далі {@code "X хв"},
     * {@code "X год"} або {@code "X год Y хв"}. Від'ємне значення обрізається до нуля.
     *
     * @param seconds тривалість у секундах
     * @return тривалість у зручному для читання форматі українською
     */
    public static String humanize(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        if (seconds < 60) {
            return "< 1 хв";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " хв";
        }
        long hours = minutes / 60;
        long mins = minutes % 60;
        return mins > 0 ? hours + " год " + mins + " хв" : hours + " год";
    }

    /**
     * Форматує проміжок між двома моментами часу за допомогою {@link #humanize(long)}.
     *
     * @param from початковий момент
     * @param to   кінцевий момент
     * @return тривалість у зручному для читання форматі українською
     */
    public static String between(Instant from, Instant to) {
        return humanize(Duration.between(from, to).getSeconds());
    }
}
