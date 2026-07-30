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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.trap.TrapIncident.Severity;

/**
 * Correlates raw {@link TrapEvent} objects into logical {@link TrapIncident} records.
 *
 * <h2>PDC state machine</h2>
 * <ol>
 *   <li>Power outage chain: {@code Active:Alarm:Loss of Mains} is the root event.
 *       Secondary traps ({@code Battery Discharging}, {@code MMS On Battery},
 *       {@code Bypass Not Available}, etc.) that arrive while the root is open are
 *       attached as details. The chain closes when {@code Cleared:Alarm:Loss of Mains} arrives
 *       or when {@code System Return to Normal} arrives on the same device.
 *   <li>Standalone alarms: any other Active/Cleared pair for which no outage chain is open.
 * </ol>
 *
 * <h2>ADC state machine</h2>
 * <p>All ADC events are standalone Active/Cleared pairs. A {@code System Return to Normal}
 * acts as a "clear all open alarms" signal for that device.
 *
 * <h2>Cold Start linking</h2>
 * <p>An ADC Cold Start that arrives within {@code coldstartLinkMinutes} after a PDC in the
 * same room has its power restored is annotated as being related to the power restoration.
 *
 * <h2>Ignored traps</h2>
 * <p>{@code Active:Alarm:Unit On Standby}, {@code Cleared:Alarm:Unit On Standby},
 * {@code Active:Alarm:Unit On}, {@code Cleared:Alarm:Unit On} are normal operational
 * transitions and are never included in the output.
 */
@Slf4j
public class TrapCorrelator {

    // Traps for which "До кінця зміни не відновлено." is not appended when unclosed —
    // they describe an ongoing process, not a fault condition requiring resolution.
    private static final Set<String> NO_UNRESOLVED_SUFFIX = Set.of(
            "Active:Alarm:Battery Discharging",
            "Active:Alarm:MMS On Battery"
    );

    // Alternative descriptions for when an incident IS closed (completed action, past tense).
    // Used instead of TRAP_DESCRIPTIONS when clearedAt != null.
    private static final Map<String, String> TRAP_DESCRIPTIONS_CLOSED = Map.of(
            "Active:Alarm:Battery Discharging", "ДБЖ перейшов на живлення від батарей."
    );

    // Traps that represent normal operational transitions — suppress entirely
    private static final Set<String> IGNORE_TRAPS = Set.of(
            "Active:Alarm:Unit On Standby",
            "Cleared:Alarm:Unit On Standby",
            "Active:Alarm:Unit Standby",      // Room4 firmware variant
            "Cleared:Alarm:Unit Standby",     // Room4 firmware variant
            "Active:Alarm:Unit On",
            "Cleared:Alarm:Unit On"
    );

    // PDC root power-outage traps — multiple firmware variants open the same chain
    // r1/r2 send "Loss of Mains"; r3/r4 send "System Input Power Problem"
    private static final Set<String> CHAIN_ROOT_ACTIVE = Set.of(
            "Active:Alarm:Loss of Mains",
            "Active:Alarm:System Input Power Problem"
    );
    private static final Set<String> CHAIN_ROOT_CLEARED = Set.of(
            "Cleared:Alarm:Loss of Mains",
            "Cleared:Alarm:System Input Power Problem"
    );

    // PDC secondary traps that accompany a power outage (attached as details)
    private static final Set<String> PDC_POWER_OUTAGE_SECONDARIES = Set.of(
            "Active:Alarm:Battery Discharging",
            "Cleared:Alarm:Battery Discharging",
            "Active:Alarm:MMS On Battery",
            "Cleared:Alarm:MMS On Battery",
            "Active:Alarm:Battery Charging Inhibited",
            "Cleared:Alarm:Battery Charging Inhibited",
            "Active:Alarm:Bypass Not Available",
            "Cleared:Alarm:Bypass Not Available",
            "Active:Alarm:Low Battery",
            "Cleared:Alarm:Low Battery"
    );

    // System Return to Normal — closes all open alarms on the device
    private static final String SYSTEM_RETURN_TO_NORMAL = "System Return to Normal";

    // Maps Active trap type → its Cleared counterpart
    private static final Map<String, String> ACTIVE_TO_CLEARED = new HashMap<>();
    static {
        ACTIVE_TO_CLEARED.put("Active:Alarm:Loss of Mains",          "Cleared:Alarm:Loss of Mains");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Battery Discharging",     "Cleared:Alarm:Battery Discharging");
        ACTIVE_TO_CLEARED.put("Active:Alarm:MMS On Battery",          "Cleared:Alarm:MMS On Battery");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Bypass Not Available",    "Cleared:Alarm:Bypass Not Available");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Low Battery",             "Cleared:Alarm:Low Battery");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Unit Off",                "Cleared:Alarm:Unit Off");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Loss of Air Flow",        "Cleared:Alarm:Loss of Air Flow");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Compressor Fault",        "Cleared:Alarm:Compressor Fault");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Master Unit Communication Lost",
                                                                       "Cleared:Alarm:Master Unit Communication Lost");
        ACTIVE_TO_CLEARED.put("Active:Alarm:High Temperature",        "Cleared:Alarm:High Temperature");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Low Temperature",         "Cleared:Alarm:Low Temperature");
        ACTIVE_TO_CLEARED.put("Active:Alarm:High Humidity",           "Cleared:Alarm:High Humidity");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Low Humidity",            "Cleared:Alarm:Low Humidity");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Fan Fault",               "Cleared:Alarm:Fan Fault");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Unit Shutdown",            "Cleared:Alarm:Unit Shutdown");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Compressor Low Suction Pressure",  "Cleared:Alarm:Compressor Low Suction Pressure");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Compressor High Head Pressure",    "Cleared:Alarm:Compressor High Head Pressure");
    }

    // Maps Cleared trap type → its Active counterpart (reverse of ACTIVE_TO_CLEARED)
    private static final Map<String, String> CLEARED_TO_ACTIVE;
    static {
        CLEARED_TO_ACTIVE = new HashMap<>();
        ACTIVE_TO_CLEARED.forEach((active, cleared) -> CLEARED_TO_ACTIVE.put(cleared, active));
    }

    // Severity by active trap type
    private static final Map<String, Severity> TRAP_SEVERITY = new HashMap<>();
    static {
        TRAP_SEVERITY.put("Active:Alarm:Loss of Mains",               Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Battery Discharging",         Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:MMS On Battery",              Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Bypass Not Available",        Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Low Battery",                 Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Unit Off",                    Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Loss of Air Flow",            Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Compressor Fault",            Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Master Unit Communication Lost", Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:High Temperature",            Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Low Temperature",             Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:High Humidity",               Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Low Humidity",                Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Fan Fault",                   Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Unit Shutdown",               Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Compressor Low Suction Pressure",  Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Compressor High Head Pressure",    Severity.ALARM);
    }

    // Ukrainian descriptions for active trap types
    private static final Map<String, String> TRAP_DESCRIPTIONS = new HashMap<>();
    static {
        TRAP_DESCRIPTIONS.put("Active:Alarm:Loss of Mains",          "Зникнення мережевого живлення.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Battery Discharging",    "ДБЖ переходить на живлення від батарей.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:MMS On Battery",         "MMS переключено на живлення від батарей.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Bypass Not Available",   "Байпас недоступний.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Low Battery",            "Низький заряд батарей ДБЖ.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Unit Off",               "Пристрій вимкнено.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Loss of Air Flow",       "Відсутність потоку повітря.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor Fault",       "Несправність компресора.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Master Unit Communication Lost", "Втрата зв'язку з основним блоком.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:High Temperature",       "Висока температура.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Low Temperature",        "Низька температура.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:High Humidity",          "Висока вологість.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Low Humidity",           "Низька вологість.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Fan Fault",              "Несправність вентилятора.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Battery Charging Inhibited", "Заборонено заряджання батарей.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:System Input Power Problem",  "Проблема з мережевим живленням.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Unit Shutdown",          "Аварійне вимкнення кондиціонера.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor Low Suction Pressure", "Низький тиск всмоктування компресора.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor High Head Pressure",   "Підвищений тиск нагнітання компресора.");
        TRAP_DESCRIPTIONS.put("Monitoring Card Reboot",              "Перезапуск картки моніторингу.");
        TRAP_DESCRIPTIONS.put("Cold Start",                           "Перезапуск картки моніторингу.");
    }

    private final int correlationSeconds;
    private final int coldstartLinkSeconds;

    /**
     * @param correlationMinutes    maximum gap (minutes) between Active and Cleared to correlate
     * @param coldstartLinkMinutes  time window (minutes) within which an ADC Cold Start after a PDC
     *                              power restoration is considered related
     */
    public TrapCorrelator(int correlationMinutes, int coldstartLinkMinutes) {
        this.correlationSeconds = correlationMinutes * 60;
        this.coldstartLinkSeconds = coldstartLinkMinutes * 60;
    }

    /**
     * Correlates trap events into logical incidents.
     *
     * <p>PDC devices are processed first (to populate the power-restoration timeline used for
     * Cold Start linking on ADC devices), then ADC devices.
     *
     * @param events input trap events (any order)
     * @return list of logical incidents sorted by {@code activatedAt} ascending
     */
    public List<TrapIncident> correlate(List<TrapEvent> events) {
        // Group events by (deviceClass, hostname, ip)
        Map<String, List<TrapEvent>> byHostname = events.stream()
                .sorted(Comparator.comparing(TrapEvent::timestamp))
                .collect(Collectors.groupingBy(
                        e -> e.deviceClass() + "|" + e.hostname() + "|" + e.ip(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        // PDC power restorations: room → list of restoration instants (for Cold Start linking)
        Map<String, List<Instant>> pdcRestorations = new HashMap<>();

        List<TrapIncident> result = new ArrayList<>();

        // Pass 1: PDC
        byHostname.forEach((key, devEvents) -> {
            TrapEvent first = devEvents.get(0);
            if (!TrapEvent.CLASS_PDC.equals(first.deviceClass())) {
                return;
            }
            String room = extractRoom(first.hostname());
            result.addAll(correlatePdc(first.hostname(), first.ip(), devEvents, pdcRestorations, room));
        });

        // Pass 2: ADC
        byHostname.forEach((key, devEvents) -> {
            TrapEvent first = devEvents.get(0);
            if (!TrapEvent.CLASS_ADC.equals(first.deviceClass())) {
                return;
            }
            String room = extractRoom(first.hostname());
            List<Instant> roomRestorations = pdcRestorations.getOrDefault(room, List.of());
            result.addAll(correlateAdc(first.hostname(), first.ip(), devEvents, roomRestorations));
        });

        result.sort(Comparator.comparing(TrapIncident::activatedAt));
        return result;
    }

    private List<TrapIncident> correlatePdc(String hostname, String ip,
                                             List<TrapEvent> events,
                                             Map<String, List<Instant>> pdcRestorations,
                                             String room) {
        List<TrapIncident> incidents = new ArrayList<>();

        // State for power outage chain
        TrapEvent openOutageRoot = null;
        List<TrapEvent> openOutageSecondaries = new ArrayList<>();

        // State for standalone alarms: activeTrapType → start event
        Map<String, TrapEvent> openStandalones = new LinkedHashMap<>();

        for (TrapEvent ev : events) {
            String trap = normalizeCategory(ev.trapType());

            if (IGNORE_TRAPS.contains(trap)) {
                continue;
            }

            if (TrapDeduplicator.COLD_START.equalsIgnoreCase(trap)) {
                incidents.add(new TrapIncident(TrapEvent.CLASS_PDC, hostname, ip,
                        Severity.INFO, ev.timestamp(), ev.timestamp(),
                        TRAP_DESCRIPTIONS.get("Cold Start"), List.of()));
                continue;
            }

            if (trap.startsWith("Monitoring Card Reboot")) {
                incidents.add(new TrapIncident(TrapEvent.CLASS_PDC, hostname, ip,
                        Severity.INFO, ev.timestamp(), null,
                        TRAP_DESCRIPTIONS.get("Monitoring Card Reboot"), List.of()));
                continue;
            }

            if (SYSTEM_RETURN_TO_NORMAL.equals(trap)) {
                // Close power outage chain if open
                if (openOutageRoot != null) {
                    incidents.add(buildPowerOutageIncident(hostname, ip, openOutageRoot,
                            openOutageSecondaries, ev.timestamp()));
                    pdcRestorations.computeIfAbsent(room, r -> new ArrayList<>()).add(ev.timestamp());
                    openOutageRoot = null;
                    openOutageSecondaries.clear();
                }
                // Close all open standalones
                openStandalones.forEach((activeType, startEv) ->
                        incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, ev.timestamp())));
                openStandalones.clear();
                continue;
            }

            if (CHAIN_ROOT_ACTIVE.contains(trap)) {
                openOutageRoot = ev;
                openOutageSecondaries.clear();
                continue;
            }

            if (CHAIN_ROOT_CLEARED.contains(trap)) {
                if (openOutageRoot != null) {
                    incidents.add(buildPowerOutageIncident(hostname, ip, openOutageRoot,
                            openOutageSecondaries, ev.timestamp()));
                    pdcRestorations.computeIfAbsent(room, r -> new ArrayList<>()).add(ev.timestamp());
                    openOutageRoot = null;
                    openOutageSecondaries.clear();
                } else {
                    log.debug("TrapCorrelator PDC {}: chain root Cleared «{}» without matching Active", hostname, trap);
                }
                continue;
            }

            // Secondary power-outage traps
            if (openOutageRoot != null && PDC_POWER_OUTAGE_SECONDARIES.contains(trap)) {
                openOutageSecondaries.add(ev);
                continue;
            }

            // Standalone active trap
            if (ACTIVE_TO_CLEARED.containsKey(trap)) {
                // MMS On Battery is the second step of the same battery-switch process as
                // MMS On Battery is the final step of the same battery-switch process.
                // If Battery Discharging is already open, close it: MMS timestamp = process end.
                // Result: one incident with start (BD) + end (MMS) → description "перейшов".
                if ("Active:Alarm:MMS On Battery".equals(trap)
                        && openStandalones.containsKey("Active:Alarm:Battery Discharging")) {
                    TrapEvent startEv = openStandalones.remove("Active:Alarm:Battery Discharging");
                    incidents.add(buildStandaloneIncident(hostname, ip,
                            "Active:Alarm:Battery Discharging", startEv, ev.timestamp()));
                    continue;
                }
                if (openStandalones.containsKey(trap)) {
                    log.debug("TrapCorrelator PDC {}: duplicate Active «{}» while already open", hostname, trap);
                }
                openStandalones.put(trap, ev);
                continue;
            }

            // Standalone cleared trap
            if (CLEARED_TO_ACTIVE.containsKey(trap)) {
                String activeType = CLEARED_TO_ACTIVE.get(trap);
                TrapEvent startEv = openStandalones.remove(activeType);
                if (startEv != null) {
                    incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, ev.timestamp()));
                } else {
                    log.debug("TrapCorrelator PDC {}: Cleared «{}» without matching Active", hostname, trap);
                }
                continue;
            }

            log.debug("TrapCorrelator PDC {}: unhandled trap «{}»", hostname, trap);
        }

        // Unclosed power outage chain
        if (openOutageRoot != null) {
            incidents.add(buildPowerOutageIncident(hostname, ip, openOutageRoot, openOutageSecondaries, null));
        }

        // Unclosed standalone alarms
        openStandalones.forEach((activeType, startEv) ->
                incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, null)));

        return incidents;
    }

    private List<TrapIncident> correlateAdc(String hostname, String ip,
                                             List<TrapEvent> events,
                                             List<Instant> pdcRestorationTimes) {
        List<TrapIncident> incidents = new ArrayList<>();
        Map<String, TrapEvent> openStandalones = new LinkedHashMap<>();

        for (TrapEvent ev : events) {
            String trap = normalizeCategory(ev.trapType());

            if (IGNORE_TRAPS.contains(trap)) {
                continue;
            }

            if (SYSTEM_RETURN_TO_NORMAL.equals(trap)) {
                openStandalones.forEach((activeType, startEv) ->
                        incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, ev.timestamp())));
                openStandalones.clear();
                continue;
            }

            if (trap.startsWith("Monitoring Card Reboot")) {
                incidents.add(new TrapIncident(TrapEvent.CLASS_ADC, hostname, ip,
                        Severity.INFO, ev.timestamp(), null,
                        TRAP_DESCRIPTIONS.get("Monitoring Card Reboot"), List.of()));
                continue;
            }

            if (TrapDeduplicator.COLD_START.equalsIgnoreCase(trap)) {
                String desc = TRAP_DESCRIPTIONS.get("Cold Start");
                // Check if this Cold Start is linked to a PDC power restoration in the same room
                boolean linked = pdcRestorationTimes.stream()
                        .anyMatch(restoration ->
                                !ev.timestamp().isBefore(restoration)
                                && ev.timestamp().isBefore(restoration.plusSeconds(coldstartLinkSeconds)));
                if (linked) {
                    desc = desc + " Пов'язано з відновленням мережевого живлення.";
                }
                incidents.add(new TrapIncident(TrapEvent.CLASS_ADC, hostname, ip,
                        Severity.INFO, ev.timestamp(), ev.timestamp(), desc, List.of()));
                continue;
            }

            if (ACTIVE_TO_CLEARED.containsKey(trap)) {
                openStandalones.put(trap, ev);
                continue;
            }

            if (CLEARED_TO_ACTIVE.containsKey(trap)) {
                String activeType = CLEARED_TO_ACTIVE.get(trap);
                TrapEvent startEv = openStandalones.remove(activeType);
                if (startEv != null) {
                    incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, ev.timestamp()));
                } else {
                    log.debug("TrapCorrelator ADC {}: Cleared «{}» without matching Active", hostname, trap);
                }
                continue;
            }

            log.debug("TrapCorrelator ADC {}: unhandled trap «{}»", hostname, trap);
        }

        // Unclosed standalone alarms
        openStandalones.forEach((activeType, startEv) ->
                incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, null)));

        return incidents;
    }

    private TrapIncident buildPowerOutageIncident(String hostname, String ip,
                                                   TrapEvent root,
                                                   List<TrapEvent> secondaries,
                                                   Instant clearedAt) {
        boolean hasBattery = secondaries.stream().anyMatch(e ->
                "Active:Alarm:Battery Discharging".equals(e.trapType())
                || "Active:Alarm:MMS On Battery".equals(e.trapType()));

        StringBuilder desc = new StringBuilder(TRAP_DESCRIPTIONS.getOrDefault(
                root.trapType(), "Зникнення мережевого живлення."));
        if (hasBattery) {
            desc.append(" ДБЖ живив навантаження від батарей.");
        }
        if (clearedAt == null) {
            desc.append(" До кінця зміни не відновлено.");
        }

        List<String> details = new ArrayList<>();
        for (TrapEvent sec : secondaries) {
            String activeType = sec.trapType();
            if (CLEARED_TO_ACTIVE.containsKey(activeType)) {
                continue; // skip clear events in secondaries list
            }
            // Battery Discharging + MMS On Battery are one process; already in main description
            if ("Active:Alarm:Battery Discharging".equals(activeType)
                    || "Active:Alarm:MMS On Battery".equals(activeType)) {
                continue;
            }
            String secDesc = TRAP_DESCRIPTIONS.getOrDefault(activeType, activeType);
            details.add(secDesc);
        }

        return new TrapIncident(TrapEvent.CLASS_PDC, hostname, ip,
                Severity.ALARM, root.timestamp(), clearedAt, desc.toString(), details);
    }

    private TrapIncident buildStandaloneIncident(String hostname, String ip,
                                                  String activeTrapType,
                                                  TrapEvent startEvent,
                                                  Instant clearedAt) {
        Severity severity = TRAP_SEVERITY.getOrDefault(activeTrapType, Severity.WARNING);
        String desc;
        if (clearedAt != null && TRAP_DESCRIPTIONS_CLOSED.containsKey(activeTrapType)) {
            desc = TRAP_DESCRIPTIONS_CLOSED.get(activeTrapType);
        } else {
            desc = TRAP_DESCRIPTIONS.getOrDefault(activeTrapType, activeTrapType);
            if (clearedAt == null && !NO_UNRESOLVED_SUFFIX.contains(activeTrapType)) {
                desc = desc + " До кінця зміни не відновлено.";
            }
        }
        String deviceClass = startEvent.deviceClass();
        return new TrapIncident(deviceClass, hostname, ip, severity,
                startEvent.timestamp(), clearedAt, desc, List.of());
    }

    /**
     * Normalises Room4 firmware trap-type categories to the canonical {@code Alarm:} form.
     * <ul>
     *   <li>{@code Active:Message:X}  → {@code Active:Alarm:X}
     *   <li>{@code Active:Warning:X}  → {@code Active:Alarm:X}
     *   <li>{@code Cleared:Message:X} → {@code Cleared:Alarm:X}
     *   <li>{@code Cleared:Warning:X} → {@code Cleared:Alarm:X}
     *   <li>{@code Message:System Return to Normal} → {@code System Return to Normal}
     * </ul>
     */
    static String normalizeCategory(String trap) {
        if (trap.startsWith("Active:Message:") || trap.startsWith("Active:Warning:")) {
            // "Active:" = 7 chars; find the ':' after the category word
            return "Active:Alarm:" + trap.substring(trap.indexOf(':', 7) + 1);
        }
        if (trap.startsWith("Cleared:Message:") || trap.startsWith("Cleared:Warning:")) {
            // "Cleared:" = 8 chars
            return "Cleared:Alarm:" + trap.substring(trap.indexOf(':', 8) + 1);
        }
        if (trap.startsWith("Message:")) {
            return trap.substring("Message:".length());
        }
        return trap;
    }

    static String extractRoom(String hostname) {
        String[] parts = hostname.split("-");
        return parts.length >= 2 ? parts[1] : "";
    }
}
