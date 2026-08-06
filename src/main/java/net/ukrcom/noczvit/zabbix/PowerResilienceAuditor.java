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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

        return ConcurrentPoll.run(qualifying, this::auditOne, MAX_CONCURRENT_AUDITS, "resilience")
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Аудитує один інцидент. Повертає {@code null}, коли хост не має жодного інтерфейсного
     * SNMP-item — тобто просто нічого аналізувати (та сама умова, що і в оригінальному
     * z-ifstatus.rb: {@code next if snmp_avail == 0}).
     */
    private PowerResilienceResult auditOne(ZabbixProblem problem) {
        String host = problem.host();
        long tDown = problem.clock();
        long tUp = problem.rClock();

        List<Client.InterfaceItem> interfaces = zabbix.getInterfaceItems(host);
        if (interfaces.isEmpty()) {
            log.debug("PowerResilienceAuditor: {} has no interface items, skipping", host);
            return null;
        }

        int alreadyDown = 0;
        int stillUp = 0;
        int recovered = 0;
        int stillDownAfter = 0;
        int noData = 0;
        List<String> alreadyDownNames = new ArrayList<>();
        List<String> recoveredNames = new ArrayList<>();
        List<String> stillDownNames = new ArrayList<>();

        for (Client.InterfaceItem item : interfaces) {
            Optional<Long> before = zabbix.historyValueBefore(item, tDown);
            if (before.isEmpty()) {
                noData++;
                continue;
            }
            if (before.get() != OPERATIONAL_UP) {
                alreadyDown++;
                alreadyDownNames.add(item.name());
                continue;
            }
            stillUp++;

            Optional<Long> after = zabbix.historyValueAfter(item, tUp);
            if (after.isEmpty()) {
                noData++;
            } else if (after.get() == OPERATIONAL_UP) {
                recovered++;
                recoveredNames.add(item.name());
            } else {
                stillDownAfter++;
                stillDownNames.add(item.name());
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
            uptimeBefore = zabbix.historyValueBefore(uptimeItem.get(), tDown);
            uptimeAfter = zabbix.historyValueAfter(uptimeItem.get(), tUp);
        }

        String location = dictionary.resolvePD(host).value();

        return new PowerResilienceResult(
                host, location,
                Instant.ofEpochSecond(tDown), Instant.ofEpochSecond(tUp),
                alreadyDown, stillUp, recovered, stillDownAfter, noData,
                List.copyOf(alreadyDownNames), List.copyOf(recoveredNames), List.copyOf(stillDownNames),
                uptimeBefore, uptimeAfter, verdict);
    }
}
