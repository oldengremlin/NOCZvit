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

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Wire-format constants common to every SNMP trap email, regardless of which device sent it.
 *
 * <p>All trap mails are produced by the same trap receiver and therefore open with the same
 * header line — {@code "At <timestamp>, from <ip>, after uptime <u>, registered trap:"} — even
 * though the payload that follows differs per vendor (Emerson emits free text, RAMOS emits three
 * quoted fields). Only the shared header and its timestamp format live here; each parser keeps
 * its own body grammar.
 *
 * <p><b>Thread safety:</b> constants only. {@link DateTimeFormatter} is immutable and safe to
 * share across the Emerson and RAMOS parsing branches, which run concurrently on virtual threads.
 */
final class TrapMailFormat {

    /**
     * Regex prefix capturing the trap header: group 1 = timestamp, group 2 = source IP.
     * Callers append their own body grammar and continue numbering from group 3.
     */
    static final String HEADER_PREFIX =
            "At\\s+(\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}),\\s+from\\s+([\\d.]+),";

    /** Timestamp format used in the trap header line. */
    static final DateTimeFormatter HEADER_TIMESTAMP =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH);

    private TrapMailFormat() {
    }
}
