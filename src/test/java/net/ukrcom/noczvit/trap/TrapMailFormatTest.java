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

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrapMailFormatTest {

    private static final Pattern PREFIX_PATTERN = Pattern.compile(TrapMailFormat.HEADER_PREFIX);

    // --- HEADER_PREFIX: позитивні кейси ---

    @Test
    void headerPrefix_matchesTypicalTrapLine_capturesTimestampAndIp() {
        String line = "At 05-08-2026 13:37:15, from 10.20.30.40, after uptime 123, registered trap: FOO";
        Matcher m = PREFIX_PATTERN.matcher(line);
        assertTrue(m.find());
        assertEquals("05-08-2026 13:37:15", m.group(1));
        assertEquals("10.20.30.40", m.group(2));
    }

    @Test
    void headerPrefix_worksAsPrefixWithinLargerBodyGrammar() {
        // Callers дописують власну граматику тіла після коми — перевіряємо, що префікс
        // однаково спрацьовує, коли після нього йде довільний хвіст.
        String line = "At 01-01-2025 00:00:01, from 192.168.1.1, some vendor-specific tail \"x\" \"y\" \"z\"";
        Matcher m = PREFIX_PATTERN.matcher(line);
        assertTrue(m.find());
        assertEquals("01-01-2025 00:00:01", m.group(1));
        assertEquals("192.168.1.1", m.group(2));
    }

    // --- HEADER_PREFIX: негативні кейси ---

    @ParameterizedTest
    @ValueSource(strings = {
        "05-08-2026 13:37:15, from 10.20.30.40,", // без "At "
        "At 5-08-2026 13:37:15, from 1.2.3.4,", // день не з двох цифр
        "At 05/08/2026 13:37:15, from 1.2.3.4,", // роздільник не '-'
        "At 05-08-2026 13:37:15 from 1.2.3.4,", // немає коми після часу
        "At 05-08-2026 13:37:15, from 2001:db8::1,", // IPv6, клас символів [\\d.] не підходить
        "At 05-08-2026 13:37:15, from 10.20.30.40" // немає коми після IP
    })
    void headerPrefix_doesNotMatchMalformedHeaders(String malformed) {
        assertFalse(PREFIX_PATTERN.matcher(malformed).find());
    }

    // --- HEADER_TIMESTAMP ---

    @Test
    void headerTimestamp_parsesDdMmYyyyHhMmSs() {
        assertEquals(LocalDateTime.of(2026, 8, 5, 13, 37, 15),
                LocalDateTime.parse("05-08-2026 13:37:15", TrapMailFormat.HEADER_TIMESTAMP));
    }

    @Test
    void headerTimestamp_formatsBackToSameShape() {
        assertEquals("05-08-2026 13:37:15",
                LocalDateTime.of(2026, 8, 5, 13, 37, 15).format(TrapMailFormat.HEADER_TIMESTAMP));
    }

    @Test
    void headerPrefix_capturedGroupParsesWithHeaderTimestamp() {
        // Наскрізна перевірка: група 1 з HEADER_PREFIX і HEADER_TIMESTAMP узгоджені між собою.
        String line = "At 25-12-2025 23:59:59, from 8.8.8.8, registered trap: BAR";
        Matcher m = PREFIX_PATTERN.matcher(line);
        assertTrue(m.find());
        assertEquals(LocalDateTime.of(2025, 12, 25, 23, 59, 59),
                LocalDateTime.parse(m.group(1), TrapMailFormat.HEADER_TIMESTAMP));
    }
}
