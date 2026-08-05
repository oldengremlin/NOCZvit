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
import java.util.Set;

/**
 * One point-in-time RAMOS environmental sensor event parsed from an IMAP trap email.
 *
 * @param timestamp  when the sensor event occurred (derived from the email body header line)
 * @param ip         source IP address of the RAMOS device
 * @param state      alert state string (e.g. "High Critical", "Low Warning")
 * @param sensorName human-readable sensor name (Cyrillic hex names are already decoded)
 * @param sensorType sensor type from the MIB (e.g. "Dual Temperature N", "Dry Contact N.M")
 * @param room       normalised room label: "Room1"–"Room4", or "Інші" when not matched
 */
public record RamosTrapEvent(
        Instant timestamp,
        String ip,
        String state,
        String sensorName,
        String sensorType,
        String room
) {

    /**
     * States severe enough to highlight in the report and forward to the AI prompt — the
     * narrowest tier. A strict subset of {@link #REPORTABLE_STATES}; both sets are kept here so
     * a spelling change lands in one place instead of drifting between parser and renderer.
     */
    public static final Set<String> CRITICAL_STATES =
            Set.of("Critical", "High Critical", "Low Critical");

    /** States worth parsing at all; everything else is normal operation and is dropped. */
    public static final Set<String> REPORTABLE_STATES = Set.of(
            "Critical", "High Critical", "Low Critical",
            "High Warning", "Low Warning", "Warning",
            "Sensor Error");
}
