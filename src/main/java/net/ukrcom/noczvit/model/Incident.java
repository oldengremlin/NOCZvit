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
package net.ukrcom.noczvit.model;

import java.util.List;

/**
 * Domain model for a single network incident from either PD (Zabbix) or OSM (SDH) source.
 *
 * @param location      dict-resolved location name used for grouping (e.g. "Обухів, Малишка 2")
 * @param device        raw device identifier for PD incidents; empty string for OSM
 * @param messageTs     unix epoch of the IMAP message
 * @param eventTs       unix epoch of the actual event (equals messageTs for PD; from Trap value for OSM)
 * @param messageDateStr localized date string of the IMAP message
 * @param eventDateStr  localized date string of the actual event (equals messageDateStr for PD)
 * @param source        which system detected the incident
 * @param status        incident lifecycle status
 * @param description   plain-text event description (no HTML)
 * @param reviewNames   device/location names not found in dictionary; empty when all resolved
 */
public record Incident(
        String location,
        String device,
        long messageTs,
        long eventTs,
        String messageDateStr,
        String eventDateStr,
        Source source,
        Status status,
        String description,
        List<String> reviewNames
) {
    public enum Source { PD, OSM, ZABBIX }
    public enum Status { START, END, NONE }
}
