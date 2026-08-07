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
import java.util.regex.Matcher;
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

    /** Скільки інцидентів аудитуємо одночасно. */
    private static final int MAX_CONCURRENT_AUDITS = 5;

    /**
     * Скільки портів одного інциденту опитуємо одночасно. Реальна межа навантаження на Zabbix —
     * добуток: {@code MAX_CONCURRENT_AUDITS × MAX_CONCURRENT_PORT_PROBES} = 20 одночасних
     * запитів, тобто того ж порядку, що й SNMP-опитування (10).
     */
    private static final int MAX_CONCURRENT_PORT_PROBES = 4;

    /** UP-значення інтерфейсного item «Operational status» у цьому Zabbix-шаблоні (1 = UP). */
    private static final long OPERATIONAL_UP = 1L;

    private final Client zabbix;
    private final Dictionary dictionary;
    private final List<String> ignoredInterfacePrefixes;

    /**
     * @param zabbix
     * @param dictionary
     * @param ignoredInterfacePrefixes технічні імена інтерфейсів (частина назви item до дужки з
     *                                 описом — напр. {@code "wireguard"} для
     *                                 {@code "Interface wireguard2(...)"}), які виключаються з
     *                                 аудиту повністю, як і {@link #IGNORED_PORT}. Нижній
     *                                 регістр, порівняння через {@code startsWith}; порожній
     *                                 список — нічого не виключається. Це евристика за іменем:
     *                                 Zabbix не бачить {@code ifType}, а MikroTik дозволяє
     *                                 перейменувати інтерфейс як завгодно, тож 100%-ї гарантії
     *                                 немає — список задає сам адміністратор
     *                                 ({@code resilienceaudit.ignoreinterfaceprefixes}).
     */
    public PowerResilienceAuditor(Client zabbix, Dictionary dictionary, List<String> ignoredInterfacePrefixes) {
        this.zabbix = zabbix;
        this.dictionary = dictionary;
        this.ignoredInterfacePrefixes = ignoredInterfacePrefixes;
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

        // Перший (зовнішній) рівень паралелізму: інциденти аудитуються незалежно один від
        // одного; другий рівень — порти всередині auditOne/probePort. auditOne повертає null
        // для хостів без інтерфейсних SNMP-items, тому такі результати відсіюються тут.
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

    /**
     * Порт, який нічого не каже про резервне живлення абонентів, тож виключається з підрахунку
     * повністю, а не лише з переліку назв: порожній опис ({@code "Interface 11()"}) або явна
     * позначка вільного порту ({@code "(--free--)"}, {@code "(--unused--)"}).
     *
     * <p>Вільні порти особливо шкідливі саме тут: вони завжди DOWN, тож завжди потрапляли б у
     * «впали раніше вузла» і систематично зсували вердикт до «резервне живлення протримало
     * довше» — тобто до сприятливого висновку без жодних підстав.
     */
    private static final Pattern IGNORED_PORT =
            Pattern.compile("\\(\\s*(?:--\\s*(?:free|unused)\\s*--)?\\s*\\)$", Pattern.CASE_INSENSITIVE);

    /** Технічне ім'я інтерфейсу — частина назви item до дужки з описом, напр. {@code "wireguard2"}
     * у {@code "Interface wireguard2(...)"}. Не знаходить збігу — не наша справа судити, чому. */
    private static final Pattern INTERFACE_TECHNICAL_NAME = Pattern.compile("^Interface\\s+(\\S+?)\\(");

    /**
     * {@code true}, коли технічне ім'я порту починається з одного з {@link #ignoredInterfacePrefixes}
     * (порівняння без урахування регістру). Див. Javadoc конструктора — це евристика за іменем,
     * не за {@code ifType}, якого Zabbix тут просто не бачить.
     */
    private boolean isIgnoredInterfaceType(String name) {
        if (ignoredInterfacePrefixes.isEmpty()) {
            return false;
        }
        Matcher m = INTERFACE_TECHNICAL_NAME.matcher(name);
        if (!m.find()) {
            return false;
        }
        String technicalName = m.group(1).toLowerCase();
        return ignoredInterfacePrefixes.stream().anyMatch(technicalName::startsWith);
    }

    /** Стан одного порту за двома знімками — рівно ті гілки, які раніше були в тілі циклу. */
    private enum PortState {
        NO_DATA_AT_FALL, ALREADY_DOWN, RECOVERED, STILL_DOWN, NO_DATA_AT_RECOVERY
    }

    /**
     * @param state       до якої групи потрапив порт
     * @param observation назва порту з міткою часу знімка; {@code null} там, де знімка немає
     *                    ({@link PortState#NO_DATA_AT_FALL}, {@link PortState#NO_DATA_AT_RECOVERY})
     */
    private record PortProbe(PortState state, PowerResilienceResult.InterfaceObservation observation) {
    }

    /** Два знімки одного порту. Не має спільного стану — тому й опитується паралельно. */
    private PortProbe probePort(Client.InterfaceItem item, long tDown, long tUp) {
        Optional<Client.HistoryPoint> before = zabbix.historyValueBefore(item, tDown);
        if (before.isEmpty()) {
            return new PortProbe(PortState.NO_DATA_AT_FALL, null);
        }
        if (before.get().value() != OPERATIONAL_UP) {
            return new PortProbe(PortState.ALREADY_DOWN,
                    new PowerResilienceResult.InterfaceObservation(item.name(), before.get().clock()));
        }

        Optional<Client.HistoryPoint> after = zabbix.historyValueAfter(item, tUp);
        if (after.isEmpty()) {
            return new PortProbe(PortState.NO_DATA_AT_RECOVERY, null);
        }
        PortState state = after.get().value() == OPERATIONAL_UP ? PortState.RECOVERED : PortState.STILL_DOWN;
        return new PortProbe(state,
                new PowerResilienceResult.InterfaceObservation(item.name(), after.get().clock()));
    }

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
                .filter(i -> !IGNORED_PORT.matcher(i.name()).find())
                .filter(i -> !isIgnoredInterfaceType(i.name()))
                .toList();
        int ignoredPorts = allInterfaces.size() - interfaces.size();

        int alreadyDown = 0;
        int stillUp = 0;
        int recovered = 0;
        int stillDownAfter = 0;
        int noDataAtFall = 0;
        int noDataAtRecovery = 0;
        List<PowerResilienceResult.InterfaceObservation> alreadyDownNames = new ArrayList<>();
        List<PowerResilienceResult.InterfaceObservation> recoveredNames = new ArrayList<>();
        List<PowerResilienceResult.InterfaceObservation> stillDownNames = new ArrayList<>();

        // Порти опитуються паралельно тим самим механізмом, що й інциденти: до цього на кожен
        // порт ішло 1-2 послідовних HTTP-обходи, тож хост на 40 портів давав до 80 послідовних
        // round-trip'ів. ConcurrentPoll зберігає порядок вхідного списку, тож переліки в звіті
        // не змінюються. Агрегація — тут, у потоці-викликачі, тобто лічильники не діляться.
        List<PortProbe> probes = ConcurrentPoll.run(
                interfaces, item -> probePort(item, tDown, tUp), MAX_CONCURRENT_PORT_PROBES, "resilience-port");

        for (PortProbe probe : probes) {
            switch (probe.state()) {
                case NO_DATA_AT_FALL ->
                    // Знімка на момент падіння немає — порт не входить у totalKnown узагалі.
                    noDataAtFall++;
                case ALREADY_DOWN -> {
                    alreadyDown++;
                    alreadyDownNames.add(probe.observation());
                }
                case NO_DATA_AT_RECOVERY -> {
                    // Порт уже порахований у stillUp і в totalKnown — бракує лише другого
                    // знімка, тож це зовсім інший випадок, ніж NO_DATA_AT_FALL.
                    stillUp++;
                    noDataAtRecovery++;
                }
                case RECOVERED -> {
                    stillUp++;
                    recovered++;
                    recoveredNames.add(probe.observation());
                }
                case STILL_DOWN -> {
                    stillUp++;
                    stillDownAfter++;
                    stillDownNames.add(probe.observation());
                }
            }
        }

        int totalKnown = alreadyDown + stillUp;
        String verdict = "";
        if (totalKnown > 0 && alreadyDown == totalKnown) {
            verdict = "Усі відомі порти впали раніше за вузол — ймовірно, резервне живлення "
                    + "протримало довше за клієнтів.";
        } else if (totalKnown > 0 && alreadyDown == 0) {
            verdict = "Жоден з відомих портів не впав раніше за вузол.";
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
                alreadyDown, stillUp, recovered, stillDownAfter,
                noDataAtFall, noDataAtRecovery, ignoredPorts,
                List.copyOf(alreadyDownNames), List.copyOf(recoveredNames), List.copyOf(stillDownNames),
                uptimeBefore, uptimeAfter, restartDetectedAt, verdict);
    }
}
