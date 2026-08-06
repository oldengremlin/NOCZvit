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
import java.util.List;
import java.util.Optional;

/**
 * Аудит резервного живлення одного хоста для одного виносу: два чесних знімки (стан відомих
 * інтерфейсів на мить падіння самого хоста і на мить його відновлення), без жодного твердження
 * про те, що відбувалось між ними — весь цей проміжок хост був недоступний по SNMP, тож нічого
 * про нього не спостерігалося.
 *
 * @param host                короткий hostname у Zabbix
 * @param location            людськочитабельна локація ({@link Dictionary#resolvePD}); може
 *                             дорівнювати {@code host}, якщо не розпізнано
 * @param fallInstant         момент, коли сам хост став недоступний
 * @param recoveryInstant     момент, коли хост знову став доступний
 * @param alreadyDownAtFall   інтерфейси, що вже були DOWN на момент падіння хоста
 * @param stillUpAtFall       інтерфейси, що на той момент ще працювали — дорівнює частці
 *                             {@code recoveredBeforeUs + stillDownAfterUs + noData} цієї групи;
 *                             див. {@link #totalKnown()}
 * @param recoveredBeforeUs   з {@code stillUpAtFall} — скільки вже знову були UP на момент
 *                             відновлення хоста
 * @param stillDownAfterUs    з {@code stillUpAtFall} — скільки лишались DOWN на момент
 *                             відновлення хоста — тобто у гіршому стані, ніж сам хост
 * @param noData              інтерфейси, для яких знімок узагалі не вдалося прочитати (немає
 *                             історії на потрібну мить); виключені з усіх співвідношень вище,
 *                             показуються окремо
 * @param alreadyDownNames    інтерфейси з {@code alreadyDownAtFall}, кожен із міткою часу, коли
 *                             його стан DOWN фактично зафіксовано
 * @param recoveredNames      інтерфейси з {@code recoveredBeforeUs}, кожен із міткою часу, коли
 *                             його стан UP фактично зафіксовано
 * @param stillDownNames      інтерфейси з {@code stillDownAfterUs}, кожен із міткою часу, коли
 *                             його стан DOWN фактично зафіксовано
 * @param uptimeBefore        значення {@code system.uptime} на або до падіння, якщо доступне
 * @param uptimeAfter         значення {@code system.uptime} на або після відновлення, якщо доступне
 * @param restartDetectedAt   момент події «{@code host} has been restarted» цього ж хоста, якщо
 *                             Zabbix зафіксував її одразу після відновлення. Сам цей тригер теж
 *                             заснований на {@code system.uptime}, тож ізольовано не надійніший за
 *                             {@link #uptimeDecreased()} — довіру йому додає саме близькість у часі
 *                             до підтвердженого ICMP-відновлення цього хоста
 * @param verdict             м'яка підказка, що з'являється лише на двох однозначних краях (усі/
 *                             жоден з відомих інтерфейсів не впав раніше); порожньо в будь-якому
 *                             іншому випадку — неоднозначна середина отримує факти, а не здогад
 */
public record PowerResilienceResult(
        String host,
        String location,
        Instant fallInstant,
        Instant recoveryInstant,
        int alreadyDownAtFall,
        int stillUpAtFall,
        int recoveredBeforeUs,
        int stillDownAfterUs,
        int noData,
        List<InterfaceObservation> alreadyDownNames,
        List<InterfaceObservation> recoveredNames,
        List<InterfaceObservation> stillDownNames,
        Optional<Long> uptimeBefore,
        Optional<Long> uptimeAfter,
        Optional<Instant> restartDetectedAt,
        String verdict) {

    /**
     * Назва інтерфейсу разом з міткою часу, коли Zabbix фактично зафіксував цей стан — вона
     * може бути суттєво раніше (або пізніше) за саму мить падіння/відновлення вузла, якщо на
     * цьому інтерфейсі довкола цього часу нічого не змінювалось.
     *
     * @param name       назва інтерфейсу (див. {@link Client.InterfaceItem#name()})
     * @param observedAt коли цей стан зафіксовано, за {@link Client.HistoryPoint#clock()}
     */
    public record InterfaceObservation(String name, Instant observedAt) {
    }

    /** Інтерфейси з придатним знімком стану на момент падіння — знаменник співвідношення T_down. */
    public int totalKnown() {
        return alreadyDownAtFall + stillUpAtFall;
    }

    /**
     * {@code true}, коли Zabbix зафіксував менше значення {@code system.uptime} після
     * відновлення, ніж було до падіння. Показується лише як довідковий факт (див.
     * {@link PowerResilienceAuditor}) — лічильник може переповнюватись і без реального
     * перезавантаження, тож це не доказ ні в той, ні в інший бік.
     */
    public boolean uptimeDecreased() {
        return uptimeBefore.isPresent() && uptimeAfter.isPresent()
                && uptimeAfter.get() < uptimeBefore.get();
    }
}
