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

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatTest {

    @ParameterizedTest
    @CsvSource({
        "-100, '< 1 хв'",
        "-1, '< 1 хв'",
        "0, '< 1 хв'",
        "1, '< 1 хв'",
        "59, '< 1 хв'",
        "60, '1 хв'",
        "119, '1 хв'",
        "120, '2 хв'",
        "3599, '59 хв'",
        "3600, '1 год'",
        "3659, '1 год'",
        "3660, '1 год 1 хв'",
        "7199, '1 год 59 хв'",
        "7200, '2 год'"
    })
    void humanize_matchesKnownBoundaries(long seconds, String expected) {
        assertEquals(expected, DurationFormat.humanize(seconds));
    }

    @Test
    void humanize_secondsAreAlwaysDroppedFromOutput_evenAt59() {
        // 3599 с = 59 хв 59 с -> секунди відкидаються, лишається "59 хв", не округлюється до "1 год".
        assertEquals("59 хв", DurationFormat.humanize(3599));
        // 3659 с = 1 год 0 хв 59 с -> секунди відкидаються, "1 год" без хвилин.
        assertEquals("1 год", DurationFormat.humanize(3659));
    }

    @Test
    void between_computesSecondsFromInstants() {
        Instant from = Instant.parse("2026-08-07T10:00:00Z");
        Instant to = Instant.parse("2026-08-07T10:02:05Z");
        assertEquals("2 хв", DurationFormat.between(from, to));
    }

    @Test
    void between_zeroSpan_isLessThanOneMinute() {
        Instant t = Instant.parse("2026-08-07T10:00:00Z");
        assertEquals("< 1 хв", DurationFormat.between(t, t));
    }

    @Test
    void between_negativeSpan_toBeforeFrom_isClampedToLessThanOneMinute() {
        Instant from = Instant.parse("2026-08-07T10:05:00Z");
        Instant to = Instant.parse("2026-08-07T10:00:00Z");
        assertEquals("< 1 хв", DurationFormat.between(from, to));
    }

    @Test
    void between_hoursAndMinutes() {
        Instant from = Instant.parse("2026-08-07T10:00:00Z");
        Instant to = Instant.parse("2026-08-07T12:01:00Z");
        assertEquals("2 год 1 хв", DurationFormat.between(from, to));
    }
}
