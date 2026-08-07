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
 * Корелює «сирі» об'єкти {@link TrapEvent} у логічні записи {@link TrapIncident}.
 *
 * <h2>Автомат станів PDC</h2>
 * <ol>
 *   <li>Ланцюжок відключення живлення: {@code Active:Alarm:Loss of Mains} — кореневa подія.
 *       Вторинні трапи ({@code Battery Discharging}, {@code MMS On Battery},
 *       {@code Bypass Not Available} тощо), що надходять, поки корінь відкритий,
 *       приєднуються як деталі. Ланцюжок закривається, коли надходить
 *       {@code Cleared:Alarm:Loss of Mains} або {@code System Return to Normal} на тому ж пристрої.
 *   <li>Окремі (standalone) тривоги: будь-яка інша пара Active/Cleared, для якої не відкрито
 *       ланцюжка відключення живлення.
 * </ol>
 *
 * <h2>Автомат станів ADC</h2>
 * <p>Усі події ADC — окремі пари Active/Cleared. {@code System Return to Normal} діє як
 * сигнал «закрити всі відкриті тривоги» для цього пристрою.
 *
 * <h2>Прив'язка Cold Start</h2>
 * <p>Cold Start на ADC, що надходить протягом {@code coldstartLinkMinutes} після відновлення
 * живлення на PDC у тій самій кімнаті, позначається як пов'язаний із цим відновленням.
 *
 * <h2>Ігноровані трапи</h2>
 * <p>{@code Active:Alarm:Unit On Standby}, {@code Cleared:Alarm:Unit On Standby},
 * {@code Active:Alarm:Unit On}, {@code Cleared:Alarm:Unit On} — нормальні експлуатаційні
 * переходи, вони ніколи не потрапляють у вивід.
 */
@Slf4j
public class TrapCorrelator {

    // Трапи, для яких при незакритті НЕ додається "До кінця зміни не відновлено." —
    // вони описують процес, що триває, а не несправність, яка потребує усунення.
    private static final Set<String> NO_UNRESOLVED_SUFFIX = Set.of(
            "Active:Alarm:Battery Discharging",
            "Active:Alarm:MMS On Battery",
            "Active:Alarm:Compressor Short Cycle",
            "Active:Alarm:Compressor Low Suction Pressure"
    );

    // Миттєві (point-in-time) події виявлення: Active спрацьовує один раз, щоб
    // сигналізувати про виявлену умову, — змістовного "кінця" немає. Обробляються
    // як Cold Start — clearedAt = activatedAt. Будь-який подальший Cleared для них
    // мовчки ігнорується.
    private static final Set<String> SELF_CLOSING_ACTIVE = Set.of(
            "Active:Alarm:Compressor Short Cycle"
    );

    // Альтернативні описи для випадку, коли інцидент ЗАКРИТО (завершена дія, минулий час).
    // Використовуються замість TRAP_DESCRIPTIONS, коли clearedAt != null.
    private static final Map<String, String> TRAP_DESCRIPTIONS_CLOSED = Map.of(
            "Active:Alarm:Battery Discharging", "ДБЖ перейшов на живлення від батарей."
    );

    // Трапи, що є нормальними експлуатаційними переходами, — повністю пригнічуємо
    private static final Set<String> IGNORE_TRAPS = Set.of(
            "Active:Alarm:Unit On Standby",
            "Cleared:Alarm:Unit On Standby",
            "Active:Alarm:Unit Standby",      // варіант прошивки Room4
            "Cleared:Alarm:Unit Standby",     // варіант прошивки Room4
            "Active:Alarm:Unit On",
            "Cleared:Alarm:Unit On"
    );

    // Кореневі трапи відключення живлення PDC — різні варіанти прошивки відкривають
    // той самий ланцюжок: r1/r2 надсилають "Loss of Mains"; r3/r4 — "System Input Power Problem"
    private static final Set<String> CHAIN_ROOT_ACTIVE = Set.of(
            "Active:Alarm:Loss of Mains",
            "Active:Alarm:System Input Power Problem"
    );
    private static final Set<String> CHAIN_ROOT_CLEARED = Set.of(
            "Cleared:Alarm:Loss of Mains",
            "Cleared:Alarm:System Input Power Problem"
    );

    // Вторинні трапи PDC, що супроводжують відключення живлення (приєднуються як деталі)
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

    // System Return to Normal — закриває всі відкриті тривоги на пристрої
    private static final String SYSTEM_RETURN_TO_NORMAL = "System Return to Normal";

    // Відображення типу трапу Active → відповідний йому Cleared
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
        ACTIVE_TO_CLEARED.put("Active:Alarm:Compressor Short Cycle",           "Cleared:Alarm:Compressor Short Cycle");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Compressor Overload",              "Cleared:Alarm:Compressor Overload");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Air Filter Clogged",               "Cleared:Alarm:Air Filter Clogged");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Water Under Floor",                "Cleared:Alarm:Water Under Floor");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Condensation Detected",            "Cleared:Alarm:Condensation Detected");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Fire Alarm",                       "Cleared:Alarm:Fire Alarm");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Smoke Detected",                   "Cleared:Alarm:Smoke Detected");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Heaters Overheated",               "Cleared:Alarm:Heaters Overheated");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Humidifier Failure",               "Cleared:Alarm:Humidifier Failure");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Humidifier Problem",               "Cleared:Alarm:Humidifier Problem");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Chilled Water Low Water Flow",     "Cleared:Alarm:Chilled Water Low Water Flow");
        ACTIVE_TO_CLEARED.put("Active:Alarm:Condensate Pump High Water",       "Cleared:Alarm:Condensate Pump High Water");
    }

    // Відображення типу трапу Cleared → відповідний йому Active (обернене до ACTIVE_TO_CLEARED)
    private static final Map<String, String> CLEARED_TO_ACTIVE;
    static {
        CLEARED_TO_ACTIVE = new HashMap<>();
        ACTIVE_TO_CLEARED.forEach((active, cleared) -> CLEARED_TO_ACTIVE.put(cleared, active));
    }

    // Рівень серйозності за типом активного трапу
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
        TRAP_SEVERITY.put("Active:Alarm:Compressor Short Cycle",           Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Compressor Overload",              Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Air Filter Clogged",               Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Water Under Floor",                Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Condensation Detected",            Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Fire Alarm",                       Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Smoke Detected",                   Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Heaters Overheated",               Severity.ALARM);
        TRAP_SEVERITY.put("Active:Alarm:Humidifier Failure",               Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Humidifier Problem",               Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Chilled Water Low Water Flow",     Severity.WARNING);
        TRAP_SEVERITY.put("Active:Alarm:Condensate Pump High Water",       Severity.WARNING);
    }

    // Українські описи для типів активних трапів
    private static final Map<String, String> TRAP_DESCRIPTIONS = new HashMap<>();
    static {
        // «Немібні» трапи (назви з email-тіла, прямих відповідників у MIB немає — переклад довільний):
        TRAP_DESCRIPTIONS.put("Active:Alarm:Loss of Mains",          "Зникнення мережевого живлення.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:System Input Power Problem",  "Проблема з вхідним живленням.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor Fault",       "Несправність компресора.");
        TRAP_DESCRIPTIONS.put("Active:Alarm:Fan Fault",              "Несправність вентилятора.");

        // Описи нижче — точний переклад DESCRIPTION з LIEBERT_GP_COND-MIB:
        // lgpCondId4168BatteryDischarging: "The battery is discharging."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Battery Discharging",    "Батарея розряджається.");
        // lgpCondId4834MMSOnBattery: "The multi-module system is on battery."
        TRAP_DESCRIPTIONS.put("Active:Alarm:MMS On Battery",         "Система з кількох модулів (MMS) перейшла на живлення від батарей.");
        // lgpConditionBypassUnavailable: "This summary event is asserted when the bypass is not available."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Bypass Not Available",   "Байпас недоступний.");
        // lgpConditionLowBatteryWarning: "The battery's remaining charge is less than or equal to the configured low threshold."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Low Battery",            "Залишковий заряд батареї досяг або нижче налаштованого порогу.");
        // lgpCondId5110UnitOff: "Unit was turned off."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Unit Off",               "Пристрій вимкнено.");
        // lgpConditionLossOfAirflow: "The system has detected a loss of air flow."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Loss of Air Flow",       "Виявлено відсутність потоку повітря.");
        // lgpCondId5120MasterUnitCommunicationLost: "Communication with master unit has been lost."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Master Unit Communication Lost", "Зв'язок з головним блоком втрачено.");
        // lgpConditionHighTemperature: "The temperature has exceeded the high temperature threshold."
        TRAP_DESCRIPTIONS.put("Active:Alarm:High Temperature",       "Температура перевищила верхній поріг.");
        // lgpConditionLowTemperature: "The temperature is below the low temperature threshold."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Low Temperature",        "Температура нижче нижнього порогу.");
        // lgpConditionHighHumidity: "The humidity has exceeded the high humidity threshold."
        TRAP_DESCRIPTIONS.put("Active:Alarm:High Humidity",          "Вологість перевищила верхній поріг.");
        // lgpConditionLowHumidity: "The humidity is below the low humidity threshold."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Low Humidity",           "Вологість нижче нижнього порогу.");
        // lgpCondId4200BatteryChargingInhibited: "Battery charging is inhibited due to an external inhibit signal."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Battery Charging Inhibited", "Заряджання батарей заблоковано зовнішнім сигналом.");
        // lgpCondId5113UnitShutdown: "An event has occurred requiring the unit to be shutdown and disabled to prevent damage to the system."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Unit Shutdown",          "Пристрій вимкнено та заблоковано для запобігання пошкодженню.");
        // lgpCondId5271CompressorLowSuctionPressure: "Compressor is shut down due to low suction pressure."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor Low Suction Pressure", "Компресор зупинено через низький тиск всмоктування.");
        // lgpCondId5270CompressorHighHeadPressure: "Compressor is shut down due to high head pressure."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor High Head Pressure",   "Компресор зупинено через підвищений тиск нагнітання.");
        // lgpConditionCompressorShortCycle: "A compressor has exceeded the maximum number of starts in a minimum time period."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor Short Cycle",          "Компресор перевищив максимальну кількість запусків за мінімальний проміжок часу.");
        // lgpConditionCompressorOverload: "This system has detected a compressor overload condition."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Compressor Overload",             "Виявлено перевантаження компресора.");
        // lgpCondId5118CloggedAirFilter: "Air filter is dirty and needs to be (cleaned or) replaced."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Air Filter Clogged",              "Повітряний фільтр забруднений та потребує чистки або заміни.");
        // lgpConditionWaterUnderFloor: "Moisture has been detected under the floor."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Water Under Floor",               "Виявлено вологу під підлогою.");
        // lgpConditionCondensationDetected / lgpCondId4711: "The system has detected condensation."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Condensation Detected",           "Виявлено конденсацію.");
        // lgpConditionFireAlarm: "Fire Alarm."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Fire Alarm",                      "Пожежна тривога.");
        // lgpConditionSmokeDetected: "The system has detected smoke."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Smoke Detected",                  "Виявлено дим.");
        // lgpConditionHeatersOverheated: "Heaters Overheated."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Heaters Overheated",              "Перегрів нагрівачів.");
        // lgpConditionHumidifierFailure: "The system has detected a humidifier failure condition."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Humidifier Failure",              "Виявлено несправність зволожувача.");
        // lgpConditionHumidifierProblem: "The system has detected a humidifier problem."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Humidifier Problem",              "Виявлено проблему з зволожувачем.");
        // lgpConditionChilledWaterLowWaterFlow: "The system has detected a chilled water low water flow condition."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Chilled Water Low Water Flow",    "Виявлено низький потік охолодженої води.");
        // lgpConditionCondensatePumpHighWater: "The system has detected high water in the condensate pump."
        TRAP_DESCRIPTIONS.put("Active:Alarm:Condensate Pump High Water",      "Виявлено підвищений рівень рідини в конденсатному насосі.");
        TRAP_DESCRIPTIONS.put("Monitoring Card Reboot",              "Перезапуск картки моніторингу.");
        TRAP_DESCRIPTIONS.put("Cold Start",                           "Перезапуск картки моніторингу.");
    }

    private final int coldstartLinkSeconds;

    /**
     * @param coldstartLinkMinutes  часове вікно (у хвилинах), протягом якого ADC Cold Start
     *                              після відновлення живлення на PDC вважається пов'язаним
     */
    public TrapCorrelator(int coldstartLinkMinutes) {
        this.coldstartLinkSeconds = coldstartLinkMinutes * 60;
    }

    /** Містить результат {@link #correlate}: скорельовані інциденти плюс сирі події, що потрапили в catch-all. */
    public record CorrelationResult(List<TrapIncident> incidents, List<TrapEvent> unknownTraps) {}

    /**
     * Корелює trap-події у логічні інциденти.
     *
     * <p>Спершу обробляються пристрої PDC (щоб заповнити часову шкалу відновлень живлення,
     * яку використовує прив'язка Cold Start для ADC), потім — пристрої ADC.
     *
     * @param events вхідні trap-події (у довільному порядку)
     * @return {@link CorrelationResult} з інцидентами, відсортованими за {@code activatedAt},
     *         та сирими невідомими подіями
     */
    public CorrelationResult correlate(List<TrapEvent> events) {
        // Групуємо події за (deviceClass, hostname, ip)
        Map<String, List<TrapEvent>> byHostname = events.stream()
                .sorted(Comparator.comparing(TrapEvent::timestamp))
                .collect(Collectors.groupingBy(
                        e -> e.deviceClass() + "|" + e.hostname() + "|" + e.ip(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        // Відновлення живлення PDC: кімната → список моментів відновлення (для прив'язки Cold Start)
        Map<String, List<Instant>> pdcRestorations = new HashMap<>();

        List<TrapIncident> result = new ArrayList<>();
        List<TrapEvent> unknownTraps = new ArrayList<>();

        // Прохід 1: PDC
        byHostname.forEach((key, devEvents) -> {
            TrapEvent first = devEvents.get(0);
            if (!TrapEvent.CLASS_PDC.equals(first.deviceClass())) {
                return;
            }
            String room = extractRoom(first.hostname());
            result.addAll(correlatePdc(first.hostname(), first.ip(), devEvents, pdcRestorations, room, unknownTraps));
        });

        // Прохід 2: ADC
        byHostname.forEach((key, devEvents) -> {
            TrapEvent first = devEvents.get(0);
            if (!TrapEvent.CLASS_ADC.equals(first.deviceClass())) {
                return;
            }
            String room = extractRoom(first.hostname());
            List<Instant> roomRestorations = pdcRestorations.getOrDefault(room, List.of());
            result.addAll(correlateAdc(first.hostname(), first.ip(), devEvents, roomRestorations, unknownTraps));
        });

        result.sort(Comparator.comparing(TrapIncident::activatedAt));
        return new CorrelationResult(result, unknownTraps);
    }

    /**
     * Корелює події одного пристрою PDC: веде ланцюжок відключення живлення та окремі
     * (standalone) тривоги, повертає готові інциденти для цього пристрою.
     */
    private List<TrapIncident> correlatePdc(String hostname, String ip,
                                             List<TrapEvent> events,
                                             Map<String, List<Instant>> pdcRestorations,
                                             String room,
                                             List<TrapEvent> unknownTraps) {
        List<TrapIncident> incidents = new ArrayList<>();

        // Стан ланцюжка відключення живлення
        TrapEvent openOutageRoot = null;
        List<TrapEvent> openOutageSecondaries = new ArrayList<>();

        // Стан окремих тривог: activeTrapType → початкова подія
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
                // Точкова подія: почалась і закінчилась одномоментно, як і Cold Start
                // (той самий текст опису нижче) — clearedAt=activatedAt, а не null,
                // інакше звіт показує "—"/"незакрито" для події, що вже відбулась.
                incidents.add(new TrapIncident(TrapEvent.CLASS_PDC, hostname, ip,
                        Severity.INFO, ev.timestamp(), ev.timestamp(),
                        TRAP_DESCRIPTIONS.get("Monitoring Card Reboot"), List.of()));
                continue;
            }

            if (SYSTEM_RETURN_TO_NORMAL.equals(trap)) {
                // Закриваємо ланцюжок відключення живлення, якщо він відкритий
                if (openOutageRoot != null) {
                    incidents.add(buildPowerOutageIncident(hostname, ip, openOutageRoot,
                            openOutageSecondaries, ev.timestamp()));
                    pdcRestorations.computeIfAbsent(room, r -> new ArrayList<>()).add(ev.timestamp());
                    openOutageRoot = null;
                    openOutageSecondaries.clear();
                }
                // Закриваємо всі відкриті окремі тривоги
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

            // Вторинні трапи відключення живлення
            if (openOutageRoot != null && PDC_POWER_OUTAGE_SECONDARIES.contains(trap)) {
                openOutageSecondaries.add(ev);
                continue;
            }

            // Самозакривні миттєві події: змістовного кінця немає, clearedAt = activatedAt
            if (SELF_CLOSING_ACTIVE.contains(trap)) {
                incidents.add(buildStandaloneIncident(hostname, ip, trap, ev, ev.timestamp()));
                continue;
            }

            // Окремий активний трап
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

            // Окремий трап очищення (Cleared)
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

            // Catch-all: будь-який Active:Alarm:X, якого немає у відомих мапах → загальна окрема тривога
            if (trap.startsWith("Active:Alarm:")) {
                log.debug("TrapCorrelator PDC {}: unknown type «{}» — queued as generic standalone", hostname, ev.trapType());
                openStandalones.put(trap, ev);
                unknownTraps.add(ev);
                continue;
            }
            // Catch-all: Cleared:Alarm:X закриває відповідний загальний Active
            if (trap.startsWith("Cleared:Alarm:")) {
                String activeType = "Active:Alarm:" + trap.substring("Cleared:Alarm:".length());
                TrapEvent startEv = openStandalones.remove(activeType);
                if (startEv != null) {
                    incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, ev.timestamp()));
                } else {
                    log.debug("TrapCorrelator PDC {}: generic Cleared «{}» without matching Active", hostname, trap);
                }
                continue;
            }
            log.debug("TrapCorrelator PDC {}: truly unhandled trap «{}»", hostname, trap);
        }

        // Незакритий ланцюжок відключення живлення
        if (openOutageRoot != null) {
            incidents.add(buildPowerOutageIncident(hostname, ip, openOutageRoot, openOutageSecondaries, null));
        }

        // Незакриті окремі тривоги
        openStandalones.forEach((activeType, startEv) ->
                incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, null)));

        return incidents;
    }

    /**
     * Корелює події одного пристрою ADC: лише окремі (standalone) тривоги плюс
     * прив'язка Cold Start до відновлень живлення PDC у тій самій кімнаті.
     */
    private List<TrapIncident> correlateAdc(String hostname, String ip,
                                             List<TrapEvent> events,
                                             List<Instant> pdcRestorationTimes,
                                             List<TrapEvent> unknownTraps) {
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
                // Те саме самозакриття, що й для PDC-гілки вище — див. коментар там.
                incidents.add(new TrapIncident(TrapEvent.CLASS_ADC, hostname, ip,
                        Severity.INFO, ev.timestamp(), ev.timestamp(),
                        TRAP_DESCRIPTIONS.get("Monitoring Card Reboot"), List.of()));
                continue;
            }

            if (TrapDeduplicator.COLD_START.equalsIgnoreCase(trap)) {
                String desc = TRAP_DESCRIPTIONS.get("Cold Start");
                // Перевіряємо, чи пов'язаний цей Cold Start із відновленням живлення PDC у тій самій кімнаті
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

            // Самозакривні миттєві події: змістовного кінця немає, clearedAt = activatedAt
            if (SELF_CLOSING_ACTIVE.contains(trap)) {
                incidents.add(buildStandaloneIncident(hostname, ip, trap, ev, ev.timestamp()));
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

            // Catch-all: будь-який Active:Alarm:X, якого немає у відомих мапах → загальна окрема тривога
            if (trap.startsWith("Active:Alarm:")) {
                log.debug("TrapCorrelator ADC {}: unknown type «{}» — queued as generic standalone", hostname, ev.trapType());
                openStandalones.put(trap, ev);
                unknownTraps.add(ev);
                continue;
            }
            // Catch-all: Cleared:Alarm:X закриває відповідний загальний Active
            if (trap.startsWith("Cleared:Alarm:")) {
                String activeType = "Active:Alarm:" + trap.substring("Cleared:Alarm:".length());
                TrapEvent startEv = openStandalones.remove(activeType);
                if (startEv != null) {
                    incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, ev.timestamp()));
                } else {
                    log.debug("TrapCorrelator ADC {}: generic Cleared «{}» without matching Active", hostname, trap);
                }
                continue;
            }
            log.debug("TrapCorrelator ADC {}: truly unhandled trap «{}»", hostname, trap);
        }

        // Незакриті окремі тривоги
        openStandalones.forEach((activeType, startEv) ->
                incidents.add(buildStandaloneIncident(hostname, ip, activeType, startEv, null)));

        return incidents;
    }

    /**
     * Будує інцидент відключення живлення з кореневої події ланцюжка та її вторинних
     * трапів, формуючи опис і список деталей.
     */
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
                continue; // пропускаємо події очищення (clear) у списку вторинних
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

    /**
     * Будує окремий (standalone) інцидент з однієї активної події та (за наявності) події
     * закриття, підбираючи серйозність і опис за типом трапу.
     */
    private TrapIncident buildStandaloneIncident(String hostname, String ip,
                                                  String activeTrapType,
                                                  TrapEvent startEvent,
                                                  Instant clearedAt) {
        Severity severity = TRAP_SEVERITY.getOrDefault(activeTrapType, Severity.WARNING);
        String desc;
        if (clearedAt != null && TRAP_DESCRIPTIONS_CLOSED.containsKey(activeTrapType)) {
            desc = TRAP_DESCRIPTIONS_CLOSED.get(activeTrapType);
        } else {
            // Для невідомих типів прибираємо префікс "Active:Alarm:" заради охайнішого відображення
            String fallback = activeTrapType.startsWith("Active:Alarm:")
                    ? activeTrapType.substring("Active:Alarm:".length())
                    : activeTrapType;
            desc = TRAP_DESCRIPTIONS.getOrDefault(activeTrapType, fallback);
            if (clearedAt == null && !NO_UNRESOLVED_SUFFIX.contains(activeTrapType)) {
                desc = desc + " До кінця зміни не відновлено.";
            }
        }
        String deviceClass = startEvent.deviceClass();
        return new TrapIncident(deviceClass, hostname, ip, severity,
                startEvent.timestamp(), clearedAt, desc, List.of());
    }

    /**
     * Нормалізує категорії типів трапів прошивки Room4 до канонічної форми {@code Alarm:}.
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
            // "Active:" = 7 символів; шукаємо ':' після слова категорії
            return "Active:Alarm:" + trap.substring(trap.indexOf(':', 7) + 1);
        }
        if (trap.startsWith("Cleared:Message:") || trap.startsWith("Cleared:Warning:")) {
            // "Cleared:" = 8 символів
            return "Cleared:Alarm:" + trap.substring(trap.indexOf(':', 8) + 1);
        }
        if (trap.startsWith("Message:")) {
            return trap.substring("Message:".length());
        }
        return trap;
    }

    /** Витягує ідентифікатор кімнати з другого сегмента hostname (формат {@code class-room-...}). */
    static String extractRoom(String hostname) {
        String[] parts = hostname.split("-");
        return parts.length >= 2 ? parts[1] : "";
    }
}
