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
package net.ukrcom.noczvit.zabbix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.ConcurrentPoll;
import net.ukrcom.noczvit.Dictionary;

/**
 * Аудит резервного живлення через непрямий сигнал: для кожного вирішеного host-down інциденту
 * на SNMP-моніторованому хості — два чесних знімки стану його інтерфейсів (на мить падіння
 * вузла і на мить його відновлення), без жодної спроби описати, що відбувалося між ними: поки
 * вузол недоступний, Zabbix фізично не опитує його інтерфейси, тож даних за цей проміжок просто
 * немає.
 *
 * <p>Мета — не «хто винен», а чи вузол протримався на резервному живленні не гірше за клієнтські
 * порти. Вердикт видається лише на двох однозначних краях (усі відомі порти впали раніше вузла,
 * або жоден не впав раніше); решта — цифри без спроби класифікації, висновок за інженером.
 *
 * <p>Окремо, незалежно від вердикту по портах, перевіряється, чи Zabbix сам зафіксував подію
 * «{@code host} has been restarted» для цього ж хоста одразу після його відновлення. Сам цей
 * тригер теж заснований на {@code system.uptime.last()<10m} — тобто на тому самому лічильнику,
 * що може переповнюватись, — тож ізольовано, сам по собі, він настільки ж ненадійний, як і сирий
 * факт про uptime. Надійним його робить саме те, що аудит бере лише подію, яка настала одразу
 * після відновлення зв'язку по ICMP: малоймовірно, що переповнення лічильника випадково збіглося
 * точно з реальним ICMP-обривом і відновленням того самого хоста (див. {@link #findRestartEvent}).
 *
 * <p><b>Багатопотоковість:</b> кожен інцидент опрацьовується незалежно
 * ({@link #auditOne(ZabbixProblem)} не ділить стан з іншими викликами), фан-аут — через
 * {@link ConcurrentPoll}, той самий механізм, що й у {@code snmp.Client}.
 */
@Slf4j
public class PowerResilienceAuditor {

    /**
     * Підрядок назви тригера, що позначає повну недоступність хоста по ICMP — той самий рядок,
     * який {@code imap.Client.isPdMessage} шукає в темі листа для того ж типу події.
     */
    private static final String HOST_DOWN_TRIGGER = "Unavailable by ICMP ping";

    /**
     * Підрядок назви тригера, яким Zabbix детектує перезавантаження хоста — але сам цей тригер
     * («Template Module Generic SNMPv2») теж заснований на {@code {HOST}:system.uptime.last()<10m},
     * тобто на тому самому лічильнику, що ризикує переповнюватись. Довіру йому додає не сам факт
     * його спрацювання, а те, що аудит бере лише подію відразу після ICMP-відновлення того самого
     * хоста — див. {@link #findRestartEvent}.
     */
    private static final String RESTART_TRIGGER = "has been restarted";

    /** Скільки інцидентів аудитуємо одночасно — менше за SNMP (10), бо кожен інцидент сам по
     * собі робить кілька послідовних запитів до Zabbix (по одному-два на інтерфейс). */
    private static final int MAX_CONCURRENT_AUDITS = 5;

    /** UP-значення інтерфейсного item «Operational status» у цьому Zabbix-шаблоні (1 = UP). */
    private static final long OPERATIONAL_UP = 1L;

    private final Client zabbix;
    private final Dictionary dictionary;

    public PowerResilienceAuditor(Client zabbix, Dictionary dictionary) {
        this.zabbix = zabbix;
        this.dictionary = dictionary;
    }

    /**
     * Аудитує кожен вирішений host-down інцидент («Unavailable by ICMP ping», з відомим часом
     * відновлення) серед {@code problems}. Активні (ще не вирішені) інциденти пропускаються —
     * час відновлення невідомий, аудитувати нічого. Хости без інтерфейсних SNMP-items так само
     * пропускаються — немає даних для аналізу.
     *
     * @param problems усі Zabbix-події звітного періоду (нефільтровані)
     * @return результати аудиту, по одному на кожен інцидент, що дав дані; порядок не гарантовано
     */
    public List<PowerResilienceResult> audit(List<ZabbixProblem> problems) {
        List<ZabbixProblem> qualifying = problems.stream()
                .filter(p -> p.name().contains(HOST_DOWN_TRIGGER))
                .filter(p -> !p.isActive())
                .toList();

        if (qualifying.isEmpty()) {
            return List.of();
        }

        Map<String, List<ZabbixProblem>> restartsByHost = problems.stream()
                .filter(p -> p.name().contains(RESTART_TRIGGER))
                .collect(Collectors.groupingBy(ZabbixProblem::host));

        return ConcurrentPoll.run(qualifying, p -> auditOne(p, restartsByHost), MAX_CONCURRENT_AUDITS, "resilience")
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Знаходить подію «{@code host} has been restarted» цього ж хоста, що почалась не раніше за
     * відновлення хоста ({@code tUp}) — раніше Zabbix фізично не міг би виявити перезавантаження,
     * бо ще не опитував хост. Береться найближча така подія після {@code tUp} — жодне довільне
     * часове вікно не потрібне, бо цей нижній кордон природний, а не підібраний.
     */
    private Optional<Instant> findRestartEvent(String host, long tUp,
            Map<String, List<ZabbixProblem>> restartsByHost) {
        return restartsByHost.getOrDefault(host, List.of()).stream()
                .filter(p -> p.clock() >= tUp)
                .min(Comparator.comparingLong(ZabbixProblem::clock))
                .map(p -> Instant.ofEpochSecond(p.clock()));
    }

    /** Ознака порожнього опису порту — {@code "Interface 11()"}: такий порт нічого не каже про
     * резервне живлення, тож ігнорується повністю, а не лише в переліку назв. */
    private static final Pattern EMPTY_DESCRIPTION = Pattern.compile("\\(\\s*\\)$");

    /**
     * Аудитує один інцидент. Повертає {@code null}, коли хост не має жодного інтерфейсного
     * SNMP-item — тобто просто нічого аналізувати (та сама умова, що і в оригінальному
     * z-ifstatus.rb: {@code next if snmp_avail == 0}).
     *
     * @param restartsByHost усі «has been restarted» події звітного періоду, згруповані по хосту —
     *                       для пошуку прямого підтвердження перезавантаження саме цього вузла
     */
    private PowerResilienceResult auditOne(ZabbixProblem problem, Map<String, List<ZabbixProblem>> restartsByHost) {
        String host = problem.host();
        long tDown = problem.clock();
        long tUp = problem.rClock();

        List<Client.InterfaceItem> allInterfaces = zabbix.getInterfaceItems(host);
        if (allInterfaces.isEmpty()) {
            log.debug("PowerResilienceAuditor: {} has no interface items, skipping", host);
            return null;
        }
        List<Client.InterfaceItem> interfaces = allInterfaces.stream()
                .filter(i -> !EMPTY_DESCRIPTION.matcher(i.name()).find())
                .toList();

        int alreadyDown = 0;
        int stillUp = 0;
        int recovered = 0;
        int stillDownAfter = 0;
        int noData = 0;
        List<PowerResilienceResult.InterfaceObservation> alreadyDownNames = new ArrayList<>();
        List<PowerResilienceResult.InterfaceObservation> recoveredNames = new ArrayList<>();
        List<PowerResilienceResult.InterfaceObservation> stillDownNames = new ArrayList<>();

        for (Client.InterfaceItem item : interfaces) {
            Optional<Client.HistoryPoint> before = zabbix.historyValueBefore(item, tDown);
            if (before.isEmpty()) {
                noData++;
                continue;
            }
            if (before.get().value() != OPERATIONAL_UP) {
                alreadyDown++;
                alreadyDownNames.add(new PowerResilienceResult.InterfaceObservation(
                        item.name(), before.get().clock()));
                continue;
            }
            stillUp++;

            Optional<Client.HistoryPoint> after = zabbix.historyValueAfter(item, tUp);
            if (after.isEmpty()) {
                noData++;
            } else if (after.get().value() == OPERATIONAL_UP) {
                recovered++;
                recoveredNames.add(new PowerResilienceResult.InterfaceObservation(
                        item.name(), after.get().clock()));
            } else {
                stillDownAfter++;
                stillDownNames.add(new PowerResilienceResult.InterfaceObservation(
                        item.name(), after.get().clock()));
            }
        }

        int totalKnown = alreadyDown + stillUp;
        String verdict = "";
        if (totalKnown > 0 && alreadyDown == totalKnown) {
            verdict = "Усі відомі порти впали раніше за вузол — ймовірно, резервне живлення "
                    + "протримало довше за клієнтів.";
        } else if (totalKnown > 0 && alreadyDown == 0) {
            verdict = "Жоден з відомих портів не впав раніше за вузол — вузол здався першим, "
                    + "варто перевірити резервне живлення.";
        }

        Optional<Long> uptimeBefore = Optional.empty();
        Optional<Long> uptimeAfter = Optional.empty();
        Optional<Client.InterfaceItem> uptimeItem = zabbix.getUptimeItem(host);
        if (uptimeItem.isPresent()) {
            uptimeBefore = zabbix.historyValueBefore(uptimeItem.get(), tDown).map(Client.HistoryPoint::value);
            uptimeAfter = zabbix.historyValueAfter(uptimeItem.get(), tUp).map(Client.HistoryPoint::value);
        }

        Optional<Instant> restartDetectedAt = findRestartEvent(host, tUp, restartsByHost);

        String location = dictionary.resolvePD(host).value();

        return new PowerResilienceResult(
                host, location,
                Instant.ofEpochSecond(tDown), Instant.ofEpochSecond(tUp),
                alreadyDown, stillUp, recovered, stillDownAfter, noData,
                List.copyOf(alreadyDownNames), List.copyOf(recoveredNames), List.copyOf(stillDownNames),
                uptimeBefore, uptimeAfter, restartDetectedAt, verdict);
    }
}
