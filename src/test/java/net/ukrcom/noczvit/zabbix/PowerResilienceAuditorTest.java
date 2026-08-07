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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.ukrcom.noczvit.TestFixtures;
import net.ukrcom.noczvit.zabbix.Client.HistoryPoint;
import net.ukrcom.noczvit.zabbix.Client.InterfaceItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerResilienceAuditorTest {

    @TempDir
    Path tempDir;

    private static final long OPERATIONAL_UP = 1L;
    private static final long OPERATIONAL_DOWN = 2L;

    /**
     * Fake {@link Client} — підклас, не мок-бібліотека (проєкт її не використовує): усі мережеві
     * методи, потрібні {@link PowerResilienceAuditor}, перевизначені на пряме читання з мап, які
     * наповнює кожен тест. Конструктор {@code super(TestFixtures.config())} не робить мережевих
     * викликів.
     */
    private static class FakeZabbixClient extends Client {

        Map<String, List<InterfaceItem>> interfaceItemsByHost = new HashMap<>();
        Map<String, InterfaceItem> uptimeItemByHost = new HashMap<>();
        Map<String, HistoryPoint> beforeByItemId = new HashMap<>();
        Map<String, HistoryPoint> afterByItemId = new HashMap<>();

        FakeZabbixClient() throws IOException {
            super(TestFixtures.config());
        }

        @Override
        public List<InterfaceItem> getInterfaceItems(String hostname) {
            return interfaceItemsByHost.getOrDefault(hostname, List.of());
        }

        @Override
        public Optional<InterfaceItem> getUptimeItem(String hostname) {
            return Optional.ofNullable(uptimeItemByHost.get(hostname));
        }

        @Override
        public Optional<HistoryPoint> historyValueBefore(InterfaceItem item, long timestamp) {
            return Optional.ofNullable(beforeByItemId.get(item.itemId()));
        }

        @Override
        public Optional<HistoryPoint> historyValueAfter(InterfaceItem item, long timestamp) {
            return Optional.ofNullable(afterByItemId.get(item.itemId()));
        }
    }

    private PowerResilienceAuditor auditorWith(FakeZabbixClient fake) throws IOException {
        return new PowerResilienceAuditor(fake, TestFixtures.dictionaryPd(tempDir, Map.of()), List.of());
    }

    private PowerResilienceAuditor auditorWith(FakeZabbixClient fake, List<String> ignoredInterfacePrefixes)
            throws IOException {
        return new PowerResilienceAuditor(fake, TestFixtures.dictionaryPd(tempDir, Map.of()), ignoredInterfacePrefixes);
    }

    private static ZabbixProblem hostDown(String host, long clock, long rClock) {
        return new ZabbixProblem(host, "Unavailable by ICMP ping", clock, rClock);
    }

    // ---- 1. Фільтрація кандидатів ------------------------------------------------------------

    @Test
    void audit_skipsActiveIncidents_andUnrelatedTriggers() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(uplink)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(90), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(210), OPERATIONAL_UP));

        List<ZabbixProblem> problems = List.of(
                hostDown("host1", 100, 200),                                      // qualifying
                hostDown("host2", 100, 0),                                        // активний — rClock=0, пропускається
                new ZabbixProblem("host3", "High CPU load", 100, 200)             // не той тригер
        );

        List<PowerResilienceResult> results = auditorWith(fake).audit(problems);

        assertEquals(1, results.size());
        assertEquals("host1", results.get(0).host());
    }

    @Test
    void audit_emptyProblemList_returnsEmpty() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();

        assertTrue(auditorWith(fake).audit(List.of()).isEmpty());
    }

    // ---- 2. Хост без interface items -----------------------------------------------------------

    @Test
    void audit_hostWithoutInterfaceItems_incidentDroppedEntirely() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        // interfaceItemsByHost для "host1" навмисно не заповнено — getInterfaceItems() поверне [].

        List<PowerResilienceResult> results = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200)));

        assertTrue(results.isEmpty());
    }

    // ---- 3. Ігноровані порти ------------------------------------------------------------------

    @Test
    void audit_ignoresEmptyAndFreeMarkedPorts_butNotRealDescriptionContainingWordFree() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(
                new InterfaceItem("1", "Interface 11()", 3),                    // порожній опис — ігнор
                new InterfaceItem("2", "Interface 12(--free--)", 3),            // вільний — ігнор
                new InterfaceItem("3", "Interface 13(--FREE--)", 3),            // будь-який регістр — ігнор
                new InterfaceItem("4", "Interface 14( -- unused -- )", 3),      // пробіли всередині дужок — ігнор
                new InterfaceItem("5", "Interface 5(freedom cafe)", 3)          // "free" саме по собі НЕ тригерить
        );
        fake.interfaceItemsByHost.put("host1", items);
        // Реальний опис на порту 5 має знімок, щоб довести, що він реально враховується.
        fake.beforeByItemId.put("5", new HistoryPoint(Instant.ofEpochSecond(90), OPERATIONAL_DOWN));

        List<PowerResilienceResult> results = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200)));

        assertEquals(1, results.size());
        PowerResilienceResult r = results.get(0);
        assertEquals(4, r.ignoredPorts());
        assertEquals(1, r.totalKnown());
        assertEquals(1, r.alreadyDownAtFall());
        assertEquals("Interface 5(freedom cafe)", r.alreadyDownNames().get(0).name());
    }

    // ---- 3b. Ігнорування за префіксом технічного імені (UVPN wireguard/sstp/... ) ------------

    @Test
    void audit_ignoresConfiguredInterfaceNamePrefixes_caseInsensitively() throws IOException {
        // Непорожні описи навмисно скрізь, крім item 1 — щоб ignoredPorts=3 доводив саме новий
        // фільтр за префіксом технічного імені, а не випадково збігався з наявним IGNORED_PORT
        // (порожній опис ігнорується незалежно від списку префіксів).
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(
                new InterfaceItem("1", "Interface ether1(uplink)", 3),          // фізичний — враховуємо
                new InterfaceItem("2", "Interface wireguard1(peer-a)", 3),      // виключений префікс
                new InterfaceItem("3", "Interface WireGuard2(peer-b)", 3),      // той самий префікс, інший регістр
                new InterfaceItem("4", "Interface sstp-in3(peer-c)", 3)         // інший виключений префікс
        );
        fake.interfaceItemsByHost.put("host1", items);
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(90), OPERATIONAL_DOWN));

        List<PowerResilienceResult> results = auditorWith(fake, List.of("wireguard", "sstp"))
                .audit(List.of(hostDown("host1", 100, 200)));

        assertEquals(1, results.size());
        PowerResilienceResult r = results.get(0);
        assertEquals(3, r.ignoredPorts());
        assertEquals(1, r.totalKnown());
        assertEquals("Interface ether1(uplink)", r.alreadyDownNames().get(0).name());
    }

    @Test
    void audit_interfacePrefixFilter_matchesPrefixNotSubstring() throws IOException {
        // "wire" не повинен випадково зловити "ether-wired1" (виключення лише за ПОЧАТКОМ
        // технічного імені, не за будь-яким входженням підрядка).
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(
                new InterfaceItem("1", "Interface ether-wired1(uplink)", 3));
        fake.interfaceItemsByHost.put("host1", items);
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(90), OPERATIONAL_DOWN));

        List<PowerResilienceResult> results = auditorWith(fake, List.of("wire"))
                .audit(List.of(hostDown("host1", 100, 200)));

        assertEquals(1, results.size());
        assertEquals(0, results.get(0).ignoredPorts());
        assertEquals(1, results.get(0).totalKnown());
    }

    @Test
    void audit_emptyPrefixList_ignoresNothingByInterfaceType() throws IOException {
        // Непорожній опис навмисно — щоб не зловити вже наявний фільтр IGNORED_PORT
        // (порожнє "()" ігнорується незалежно від списку префіксів) і перевірити рівно те,
        // що тут заявлено: порожній список префіксів нічого не виключає сам по собі.
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(new InterfaceItem("1", "Interface wireguard1(peer-a)", 3));
        fake.interfaceItemsByHost.put("host1", items);
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(90), OPERATIONAL_DOWN));

        // Дефолт — auditorWith(fake) без списку — нічого не виключає.
        List<PowerResilienceResult> results = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200)));

        assertEquals(1, results.size());
        assertEquals(0, results.get(0).ignoredPorts());
        assertEquals(1, results.get(0).totalKnown());
    }

    // ---- 4. Бакетинг за двома знімками ---------------------------------------------------------

    @Test
    void audit_bucketsPortsAcrossBothSnapshots_andInvariantHolds() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(
                new InterfaceItem("1", "Interface 1(no-data-at-fall)", 3),
                new InterfaceItem("2", "Interface 2(already-down)", 3),
                new InterfaceItem("3", "Interface 3(recovered)", 3),
                new InterfaceItem("4", "Interface 4(still-down)", 3),
                new InterfaceItem("5", "Interface 5(no-data-at-recovery)", 3)
        );
        fake.interfaceItemsByHost.put("host1", items);

        // item 1: без знімка "before" -> NO_DATA_AT_FALL, не входить у totalKnown.
        // item 2: DOWN до падіння -> ALREADY_DOWN.
        fake.beforeByItemId.put("2", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_DOWN));
        // item 3: UP до падіння, UP після відновлення -> RECOVERED.
        fake.beforeByItemId.put("3", new HistoryPoint(Instant.ofEpochSecond(96), OPERATIONAL_UP));
        fake.afterByItemId.put("3", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));
        // item 4: UP до падіння, DOWN після відновлення -> STILL_DOWN.
        fake.beforeByItemId.put("4", new HistoryPoint(Instant.ofEpochSecond(97), OPERATIONAL_UP));
        fake.afterByItemId.put("4", new HistoryPoint(Instant.ofEpochSecond(206), OPERATIONAL_DOWN));
        // item 5: UP до падіння, немає знімка після відновлення -> NO_DATA_AT_RECOVERY (лишається у stillUpAtFall).
        fake.beforeByItemId.put("5", new HistoryPoint(Instant.ofEpochSecond(98), OPERATIONAL_UP));

        List<PowerResilienceResult> results = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200)));

        assertEquals(1, results.size());
        PowerResilienceResult r = results.get(0);

        assertEquals(1, r.noDataAtFall());
        assertEquals(1, r.alreadyDownAtFall());
        assertEquals(3, r.stillUpAtFall());       // recovered + stillDown + noDataAtRecovery
        assertEquals(1, r.recoveredBeforeUs());
        assertEquals(1, r.stillDownAfterUs());
        assertEquals(1, r.noDataAtRecovery());
        assertEquals(4, r.totalKnown());          // 1 already-down + 3 still-up, БЕЗ noDataAtFall

        // Ключовий інваріант з задачі.
        assertEquals(r.stillUpAtFall(), r.recoveredBeforeUs() + r.stillDownAfterUs() + r.noDataAtRecovery());
    }

    @Test
    void audit_stillUpAtFall_countsNoDataAtRecoveryPort_evenThoughNoObservationRecorded() throws IOException {
        // NO_DATA_AT_RECOVERY порт лишається у stillUpAtFall, але не отримує запису у жоден
        // з іменних списків (той список — тільки для recovered/still-down/already-down).
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(flaky)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        // afterByItemId навмисно порожній.

        List<PowerResilienceResult> results = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200)));

        PowerResilienceResult r = results.get(0);
        assertEquals(1, r.stillUpAtFall());
        assertEquals(1, r.noDataAtRecovery());
        assertEquals(0, r.recoveredBeforeUs());
        assertEquals(0, r.stillDownAfterUs());
        assertTrue(r.recoveredNames().isEmpty());
        assertTrue(r.stillDownNames().isEmpty());
    }

    // ---- 5. Вердикт ------------------------------------------------------------------------

    @Test
    void audit_verdict_allKnownPortsAlreadyDown_exactText() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(
                new InterfaceItem("1", "Interface 1(a)", 3),
                new InterfaceItem("2", "Interface 2(b)", 3)
        );
        fake.interfaceItemsByHost.put("host1", items);
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_DOWN));
        fake.beforeByItemId.put("2", new HistoryPoint(Instant.ofEpochSecond(96), OPERATIONAL_DOWN));

        PowerResilienceResult r = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200))).get(0);

        assertEquals("Усі відомі порти впали раніше за вузол — ймовірно, резервне живлення "
                + "протримало довше за клієнтів.", r.verdict());
    }

    @Test
    void audit_verdict_noneKnownPortsDown_exactTextWithoutExtraAdvice() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(a)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));

        PowerResilienceResult r = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200))).get(0);

        assertEquals("Жоден з відомих портів не впав раніше за вузол.", r.verdict());
        // Нещодавно спрощено: жодної додаткової поради на кшталт "варто перевірити" бути не повинно.
        assertFalse(r.verdict().toLowerCase().contains("варто перевірити"));
    }

    @Test
    void audit_verdict_mixedKnownPorts_emptyVerdict() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(
                new InterfaceItem("1", "Interface 1(a)", 3),
                new InterfaceItem("2", "Interface 2(b)", 3)
        );
        fake.interfaceItemsByHost.put("host1", items);
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_DOWN));
        fake.beforeByItemId.put("2", new HistoryPoint(Instant.ofEpochSecond(96), OPERATIONAL_UP));
        fake.afterByItemId.put("2", new HistoryPoint(Instant.ofEpochSecond(206), OPERATIONAL_UP));

        PowerResilienceResult r = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200))).get(0);

        assertEquals("", r.verdict());
    }

    @Test
    void audit_verdict_totalKnownZero_emptyVerdict() throws IOException {
        // totalKnown()==0 (усі порти noDataAtFall) — не край, вердикт не формується.
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(a)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        // Без запису в beforeByItemId — historyValueBefore поверне empty.

        PowerResilienceResult r = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200))).get(0);

        assertEquals(0, r.totalKnown());
        assertEquals("", r.verdict());
    }

    // ---- 6. restartDetectedAt (windowless-кореляція) ------------------------------------------

    @Test
    void audit_restartDetectedAt_picksNearestEventAtOrAfterRecovery() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(a)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));

        // tUp = 200. Кандидати навмисно НЕ у відсортованому порядку в списку, щоб довести, що
        // обирається саме мінімальний clock >= tUp, а не перший/останній елемент списку.
        List<ZabbixProblem> problems = List.of(
                hostDown("host1", 100, 200),
                new ZabbixProblem("host1", "host1 has been restarted", 250, 260),  // найпізніший
                new ZabbixProblem("host1", "host1 has been restarted", 199, 210),  // до tUp — ІГНОРУЄТЬСЯ
                new ZabbixProblem("host1", "host1 has been restarted", 220, 230)   // найближчий після tUp
        );

        PowerResilienceResult r = auditorWith(fake).audit(problems).get(0);

        assertTrue(r.restartDetectedAt().isPresent());
        assertEquals(Instant.ofEpochSecond(220), r.restartDetectedAt().get());
    }

    @Test
    void audit_restartDetectedAt_eventExactlyAtRecovery_isEligible() throws IOException {
        // clock >= tUp, межа включна.
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(a)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));

        List<ZabbixProblem> problems = List.of(
                hostDown("host1", 100, 200),
                new ZabbixProblem("host1", "host1 has been restarted", 200, 210)
        );

        PowerResilienceResult r = auditorWith(fake).audit(problems).get(0);

        assertEquals(Optional.of(Instant.ofEpochSecond(200)), r.restartDetectedAt());
    }

    @Test
    void audit_restartDetectedAt_noEligibleEvent_isEmpty() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(a)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));

        List<ZabbixProblem> problems = List.of(
                hostDown("host1", 100, 200),
                new ZabbixProblem("host1", "host1 has been restarted", 150, 160)  // до tUp
        );

        PowerResilienceResult r = auditorWith(fake).audit(problems).get(0);

        assertTrue(r.restartDetectedAt().isEmpty());
    }

    @Test
    void audit_restartDetectedAt_onlyMatchesSameHost() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(a)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));

        List<ZabbixProblem> problems = List.of(
                hostDown("host1", 100, 200),
                new ZabbixProblem("host2", "host2 has been restarted", 220, 230)
        );

        PowerResilienceResult r = auditorWith(fake).audit(problems).get(0);

        assertTrue(r.restartDetectedAt().isEmpty());
    }

    // ---- 7. uptimeBefore/uptimeAfter -----------------------------------------------------------

    @Test
    void audit_uptime_bothEmptyWhenNoUptimeItem() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem item = new InterfaceItem("1", "Interface 1(a)", 3);
        fake.interfaceItemsByHost.put("host1", List.of(item));
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));
        // uptimeItemByHost для host1 навмисно не заповнено.

        PowerResilienceResult r = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200))).get(0);

        assertTrue(r.uptimeBefore().isEmpty());
        assertTrue(r.uptimeAfter().isEmpty());
    }

    @Test
    void audit_uptime_readsFromUptimeItemViaBeforeAfterSnapshots() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        InterfaceItem port = new InterfaceItem("1", "Interface 1(a)", 3);
        InterfaceItem uptimeItem = new InterfaceItem("uptime-1", "system.uptime", 3);
        fake.interfaceItemsByHost.put("host1", List.of(port));
        fake.uptimeItemByHost.put("host1", uptimeItem);
        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(95), OPERATIONAL_UP));
        fake.afterByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(205), OPERATIONAL_UP));
        fake.beforeByItemId.put("uptime-1", new HistoryPoint(Instant.ofEpochSecond(95), 5000L));
        fake.afterByItemId.put("uptime-1", new HistoryPoint(Instant.ofEpochSecond(205), 30L));

        PowerResilienceResult r = auditorWith(fake).audit(List.of(hostDown("host1", 100, 200))).get(0);

        assertEquals(Optional.of(5000L), r.uptimeBefore());
        assertEquals(Optional.of(30L), r.uptimeAfter());
        assertTrue(r.uptimeDecreased());
    }

    // ---- 8. Повний "щасливий" сценарій кінець-в-кінець ----------------------------------------

    @Test
    void audit_fullRealisticScenario_endToEnd() throws IOException {
        FakeZabbixClient fake = new FakeZabbixClient();
        List<InterfaceItem> items = List.of(
                new InterfaceItem("1", "Interface 1(client-a)", 3),      // already down
                new InterfaceItem("2", "Interface 2(client-b)", 3),      // recovered
                new InterfaceItem("3", "Interface 3(client-c)", 3),      // still down after
                new InterfaceItem("4", "Interface 4()", 3),              // ignored: empty desc
                new InterfaceItem("5", "Interface 5(--free--)", 3)       // ignored: free
        );
        InterfaceItem uptimeItem = new InterfaceItem("uptime-1", "system.uptime", 3);
        fake.interfaceItemsByHost.put("host1", items);
        fake.uptimeItemByHost.put("host1", uptimeItem);

        fake.beforeByItemId.put("1", new HistoryPoint(Instant.ofEpochSecond(90), OPERATIONAL_DOWN));
        fake.beforeByItemId.put("2", new HistoryPoint(Instant.ofEpochSecond(91), OPERATIONAL_UP));
        fake.afterByItemId.put("2", new HistoryPoint(Instant.ofEpochSecond(211), OPERATIONAL_UP));
        fake.beforeByItemId.put("3", new HistoryPoint(Instant.ofEpochSecond(92), OPERATIONAL_UP));
        fake.afterByItemId.put("3", new HistoryPoint(Instant.ofEpochSecond(212), OPERATIONAL_DOWN));
        fake.beforeByItemId.put("uptime-1", new HistoryPoint(Instant.ofEpochSecond(90), 100000L));
        fake.afterByItemId.put("uptime-1", new HistoryPoint(Instant.ofEpochSecond(210), 40L));

        List<ZabbixProblem> problems = List.of(
                hostDown("host1", 100, 200),
                new ZabbixProblem("host1", "host1 has been restarted", 205, 215)
        );

        List<PowerResilienceResult> results = auditorWith(fake).audit(problems);

        assertEquals(1, results.size());
        PowerResilienceResult r = results.get(0);

        assertEquals("host1", r.host());
        assertEquals("host1", r.location());   // порожній словник -> location == host
        assertEquals(Instant.ofEpochSecond(100), r.fallInstant());
        assertEquals(Instant.ofEpochSecond(200), r.recoveryInstant());
        assertEquals(1, r.alreadyDownAtFall());
        assertEquals(2, r.stillUpAtFall());
        assertEquals(1, r.recoveredBeforeUs());
        assertEquals(1, r.stillDownAfterUs());
        assertEquals(0, r.noDataAtFall());
        assertEquals(0, r.noDataAtRecovery());
        assertEquals(2, r.ignoredPorts());
        assertEquals(3, r.totalKnown());
        assertEquals("", r.verdict());          // 1 з 3 — неоднозначна середина
        assertEquals(List.of("Interface 1(client-a)"), r.alreadyDownNames().stream().map(o -> o.name()).toList());
        assertEquals(List.of("Interface 2(client-b)"), r.recoveredNames().stream().map(o -> o.name()).toList());
        assertEquals(List.of("Interface 3(client-c)"), r.stillDownNames().stream().map(o -> o.name()).toList());
        assertEquals(Optional.of(100000L), r.uptimeBefore());
        assertEquals(Optional.of(40L), r.uptimeAfter());
        assertTrue(r.uptimeDecreased());
        assertEquals(Optional.of(Instant.ofEpochSecond(205)), r.restartDetectedAt());
    }
}
