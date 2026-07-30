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
import java.util.List;

/**
 * A correlated trap incident representing a single logical event on one device.
 *
 * @param deviceClass  {@link TrapEvent#CLASS_ADC} or {@link TrapEvent#CLASS_PDC}
 * @param hostname     device hostname
 * @param ip           device IP address
 * @param severity     event severity
 * @param activatedAt  when the event started
 * @param clearedAt    when the event ended; {@code null} if still open at end of shift
 * @param description  Ukrainian description of the event
 * @param details      optional additional detail lines (e.g. secondary traps that fired)
 */
public record TrapIncident(
        String deviceClass,
        String hostname,
        String ip,
        Severity severity,
        Instant activatedAt,
        Instant clearedAt,
        String description,
        List<String> details) {

    public enum Severity {
        ALARM, WARNING, INFO
    }

    public boolean isClosed() {
        return clearedAt != null;
    }

    /** Extracts the room identifier from a hostname like {@code adc-r1-1} → {@code r1}. */
    public String roomId() {
        String[] parts = hostname.split("-");
        return parts.length >= 2 ? parts[1] : "";
    }
}
