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

import net.ukrcom.noczvit.model.Incident.Status;

/**
 * Shared wording for {@link Incident} descriptions and the alert-subject → {@link Status}
 * mapping, used by every incident source (the four IMAP parsers and the Zabbix API converter).
 *
 * <p>Keeping this in one place matters for the report itself: {@code IncidentSectionBuilder}
 * rewrites a paired row's description by replacing the literal {@code "початок інциденту, "} /
 * {@code "кінець інциденту, "} fragments, so any source that words its prefix differently would
 * silently stop pairing correctly.
 *
 * <p><b>Thread safety:</b> stateless — all methods are static and operate only on their
 * arguments. Incident sources run concurrently on virtual threads.
 */
public final class IncidentDescriptions {

    /** Source label for Zabbix-originated incidents (both IMAP alerts and the Zabbix API). */
    public static final String SOURCE_ZABBIX = "Zabbix";

    /** Source label for OSM/SDH-originated incidents. */
    public static final String SOURCE_OSM = "OSM";

    private IncidentDescriptions() {
    }

    /**
     * Maps an alert subject to a lifecycle status: {@code " Resolved:"} → {@link Status#END},
     * {@code " Problem:"} → {@link Status#START}, anything else → {@link Status#NONE}.
     *
     * @param subject raw mail subject
     * @return resolved status; never null
     */
    public static Status resolveStatus(String subject) {
        if (subject.contains(" Resolved:")) {
            return Status.END;
        }
        if (subject.contains(" Problem:")) {
            return Status.START;
        }
        return Status.NONE;
    }

    /**
     * Builds the description prefix, e.g. {@code "Zabbix зареєстровано початок інциденту, "}.
     * {@link Status#NONE} yields the bare {@code "<source> зареєстровано "} form.
     *
     * @param source source label ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status incident lifecycle status
     * @return prefix ending with a trailing space
     */
    public static String statePrefix(String source, Status status) {
        return statePrefix(source, status, "");
    }

    /**
     * Builds the description prefix, with an explicit wording for {@link Status#NONE}.
     *
     * @param source   source label ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status   incident lifecycle status
     * @param noneText what follows {@code "зареєстровано "} for {@link Status#NONE} — OSM says
     *                 {@code "інцидент, "}, Zabbix sources leave it empty
     * @return prefix ending with a trailing space
     */
    public static String statePrefix(String source, Status status, String noneText) {
        return source + " зареєстровано " + switch (status) {
            case START ->
                "початок інциденту, ";
            case END ->
                "кінець інциденту, ";
            case NONE ->
                noneText;
        };
    }

    /**
     * Assembles a full description from the state prefix and an event phrase, collapsing runs of
     * whitespace — event phrases are concatenated from dictionary values that may carry stray
     * spacing.
     *
     * @param source source label ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status incident lifecycle status
     * @param event  event phrase (e.g. {@code "зникнення зв'язку з обладнанням на Прахових 50"})
     * @return normalised description
     */
    public static String describe(String source, Status status, String event) {
        return describe(source, status, event, "");
    }

    /**
     * Assembles a full description, with an explicit wording for {@link Status#NONE}.
     *
     * @param source   source label ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status   incident lifecycle status
     * @param event    event phrase
     * @param noneText what follows {@code "зареєстровано "} for {@link Status#NONE}
     * @return normalised description
     */
    public static String describe(String source, Status status, String event, String noneText) {
        return (statePrefix(source, status, noneText) + event).replaceAll("\\s+", " ");
    }
}
