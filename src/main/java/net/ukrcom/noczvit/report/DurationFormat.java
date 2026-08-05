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
 * Single wording for the «Тривалість» column, shared by every table in the report.
 *
 * <p>Sub-minute spans read {@code "< 1 хв"} rather than a second count: the report is written for
 * a shift handover, where «майже миттєво» is the useful fact and an exact second count is noise.
 * It also avoids the nonsensical {@code "0 с"} that point-in-time events (Cold Start, Compressor
 * Short Cycle) produced — those close at their own timestamp and have no duration at all.
 *
 * <p><b>Thread safety:</b> stateless — static methods over their arguments only.
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    /**
     * Formats a duration in seconds: {@code "< 1 хв"} below a minute, then {@code "X хв"},
     * {@code "X год"} or {@code "X год Y хв"}. Negative input is clamped to zero.
     *
     * @param seconds duration in seconds
     * @return human-readable Ukrainian duration
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
     * Formats the span between two instants using {@link #humanize(long)}.
     *
     * @param from start instant
     * @param to   end instant
     * @return human-readable Ukrainian duration
     */
    public static String between(Instant from, Instant to) {
        return humanize(Duration.between(from, to).getSeconds());
    }
}
