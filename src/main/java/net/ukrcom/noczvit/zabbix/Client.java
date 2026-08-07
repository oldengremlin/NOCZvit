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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;

/**
 * Клієнт Zabbix API та web-інтерфейсу для отримання історичних подій і завантаження
 * зображень графіків.
 *
 * <p>Підтримує дві сесії: сесію JSON-RPC API (для {@code event.get}, {@code host.get},
 * {@code graph.get}) та сесію web UI (для завантаження PNG через {@code chart2.php}). Обидві
 * встановлюються через {@link #login()}. ID хостів та ID графіків кешуються, щоб уникнути
 * повторних викликів API при формуванні одного й того ж розділу звіту для кількох інцидентів.
 */
@Slf4j
public class Client {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicInteger ID_GEN = new AtomicInteger(1);
    private static final Gson GSON = new Gson();

    // Без цього завислий front-end Zabbix підвісить увесь cron-запуск на невизначений час:
    // java.net.http.HttpClient не має типового таймауту з'єднання чи читання.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** chart2.php рендерить PNG і закономірно повільніший за JSON-RPC endpoint. */
    private static final Duration GRAPH_TIMEOUT = Duration.ofSeconds(60);

    private final Config config;
    private final HttpClient http;
    private volatile String authToken;

    private final ConcurrentHashMap<String, String> hostIdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> graphIdCache = new ConcurrentHashMap<>();
    // Один хост нерідко падає кілька разів за зміну, і кожен такий інцидент аудитується окремо —
    // без кешу список items перезапитувався б щоразу заново.
    private final ConcurrentHashMap<String, List<InterfaceItem>> interfaceItemsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Optional<InterfaceItem>> uptimeItemCache = new ConcurrentHashMap<>();

    /**
     * Створює клієнт Zabbix. HTTP-клієнт налаштовано зі спільним сховищем cookie, щоб
     * cookie web-сесії ({@code zbx_sessionid}), встановлений під час {@link #webLogin()},
     * автоматично додавався до кожного наступного запиту {@code chart2.php}.
     */
    public Client(Config config) {
        this.config = config;
        // CookieManager зберігає zbx_sessionid після web-логіну та автоматично надсилає
        // його з кожним наступним запитом (включно з chart2.php).
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Виконує автентифікацію і в Zabbix JSON-RPC API, і у web UI Zabbix.
     * Для коректної роботи зображень графіків обидві сесії мають бути успішними.
     *
     * @return {@code true}, якщо і API-, і web-логін пройшли успішно
     */
    public boolean login() {
        boolean apiOk = apiLogin();
        boolean webOk = webLogin();
        log.debug("Zabbix login: apiOk={}, webOk={}", apiOk, webOk);
        return apiOk && webOk;
    }

    /** Автентифікується через {@code user.login} і зберігає отриманий auth-токен. */
    private boolean apiLogin() {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("user", config.getZabbixUsername());
            params.addProperty("password", config.getZabbixPassword());
            JsonObject resp = apiCall("user.login", params, null);
            JsonElement result = resp.get("result");
            if (result != null && result.isJsonPrimitive()) {
                authToken = result.getAsString();
                log.debug("Zabbix API login OK, token={}...",
                        authToken.substring(0, Math.min(8, authToken.length())));
                return true;
            }
            // Лише поле error, а не вся відповідь: це WARN, тобто пишеться і в продакшні, а
            // формат відповіді Zabbix може змінитися й почати нести зайве.
            log.warn("Zabbix API login failed: {}", resp.get("error"));
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Zabbix API login error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Автентифікується через form POST на {@code index.php}. У разі успіху Zabbix встановлює
     * cookie {@code zbx_sessionid}, який зберігається у спільному {@code CookieManager} і
     * автоматично надсилається з кожним наступним запитом {@code chart2.php}.
     */
    private boolean webLogin() {
        try {
            String formBody = "name=" + URLEncoder.encode(config.getZabbixUsername(), StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(config.getZabbixPassword(), StandardCharsets.UTF_8)
                    + "&autologin=1&enter=Sign+in";

            String loginUrl = config.getZabbixUrl() + "/index.php";
            log.debug("Zabbix web login POST: {}", loginUrl);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(loginUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("Zabbix web login response: HTTP {}, final URI: {}", resp.statusCode(), resp.uri());

            boolean hasCookie = http.cookieHandler()
                    .map(ch -> ch instanceof CookieManager cm
                    && cm.getCookieStore().getCookies().stream()
                            .anyMatch(c -> c.getName().startsWith("zbx_session")))
                    .orElse(false);

            log.debug("Zabbix web session cookie present: {}", hasCookie);
            if (log.isDebugEnabled() && http.cookieHandler().isPresent()) {
                CookieManager cm = (CookieManager) http.cookieHandler().get();
                cm.getCookieStore().getCookies().forEach(c
                        -> log.debug("  cookie: {}={}...", c.getName(),
                                c.getValue().substring(0, Math.min(8, c.getValue().length()))));
            }

            return hasCookie;
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Zabbix web login error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Повертає список подій Zabbix за вказаний період зміни. Запитує лише
     * Середні (3), Високі (4) та Критичні (5) severity. Повертає порожній
     * список якщо авторизація не виконана або при помилці.
     *
     * Використовує event.get (таблиця events) замість problem.get (таблиця
     * problems), бо problem.get повертає лише активні або нещодавно вирішені
     * проблеми — Zabbix housekeeping видаляє вирішені записи з problem-таблиці.
     *
     * @param from
     * @param to
     * @return
     */
    public List<ZabbixProblem> getProblems(LocalDateTime from, LocalDateTime to) {
        if (authToken == null) {
            return Collections.emptyList();
        }

        long ctFrom = from.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctTo = to.atZone(ZoneId.systemDefault()).toEpochSecond();

        try {
            JsonObject params = new JsonObject();
            params.addProperty("source", 0);                              // події на основі тригерів
            params.addProperty("object", 0);                              // обʼєкти-тригери
            params.add("value", GSON.toJsonTree(new int[]{1}));           // 1 = PROBLEM (не відновлення)
            params.addProperty("time_from", ctFrom);
            params.addProperty("time_till", ctTo);
            params.add("severities", GSON.toJsonTree(new int[]{3, 4, 5}));
            // objectid = trigger ID (для fallback через trigger.get), r_eventid = ID події відновлення
            params.add("output", GSON.toJsonTree(new String[]{"eventid", "objectid", "r_eventid", "name", "clock"}));
            params.add("selectHosts", GSON.toJsonTree(new String[]{"host"}));

            JsonObject resp = apiCall("event.get", params, authToken);
            JsonArray result = resp.getAsJsonArray("result");
            if (result == null) {
                return Collections.emptyList();
            }

            record EventEntry(String objectId, String rEventId, String name, long clock, String host) {

            }
            List<EventEntry> entries = new ArrayList<>(result.size());
            List<String> rEventIds = new ArrayList<>();
            List<String> missingHostTriggerIds = new ArrayList<>();

            for (JsonElement el : result) {
                JsonObject obj = el.getAsJsonObject();
                String objectId = obj.get("objectid").getAsString();
                String rEventId = obj.get("r_eventid").getAsString(); // "0" якщо ще активна
                String name = obj.get("name").getAsString();
                long clock = obj.get("clock").getAsLong();

                JsonArray hosts = obj.getAsJsonArray("hosts");
                String host = (hosts != null && !hosts.isEmpty())
                              ? hosts.get(0).getAsJsonObject().get("host").getAsString()
                              : "";

                if (host.isBlank()) {
                    missingHostTriggerIds.add(objectId);
                }
                if (!"0".equals(rEventId)) {
                    rEventIds.add(rEventId);
                }
                entries.add(new EventEntry(objectId, rEventId, name, clock, host));
            }

            // Час відновлення: отримуємо clock відновлювальних подій одним запитом
            Map<String, Long> rEventClocks = fetchEventClocks(rEventIds);
            // Fallback для template-based тригерів, де selectHosts повернув порожній масив
            Map<String, String> triggerHostMap = resolveTriggerHosts(missingHostTriggerIds);

            List<ZabbixProblem> problems = new ArrayList<>(entries.size());
            for (EventEntry e : entries) {
                String host = e.host().isBlank()
                              ? triggerHostMap.getOrDefault(e.objectId(), "")
                              : e.host();
                long rClock = "0".equals(e.rEventId()) ? 0L
                              : rEventClocks.getOrDefault(e.rEventId(), 0L);
                problems.add(new ZabbixProblem(host, e.name(), e.clock(), rClock));
            }

            log.debug("Zabbix event.get: {} подій у [{}, {}]", problems.size(), from, to);
            return problems;

        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Zabbix event.get помилка: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Отримує {@code clock} (unix timestamp) для пакета ID подій відновлення одним викликом
     * {@code event.get}. Використовується для заповнення {@link ZabbixProblem#rClock()}.
     */
    private Map<String, Long> fetchEventClocks(List<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            JsonObject params = new JsonObject();
            params.add("eventids", GSON.toJsonTree(eventIds.toArray(String[]::new)));
            params.add("output", GSON.toJsonTree(new String[]{"eventid", "clock"}));

            JsonObject resp = apiCall("event.get", params, authToken);
            JsonArray result = resp.getAsJsonArray("result");
            if (result == null) {
                return Collections.emptyMap();
            }

            Map<String, Long> map = new HashMap<>();
            for (JsonElement el : result) {
                JsonObject obj = el.getAsJsonObject();
                map.put(obj.get("eventid").getAsString(), obj.get("clock").getAsLong());
            }
            return map;
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Zabbix event.get (recovery clocks) помилка: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Розвʼязує hostname для тих ID тригерів, для яких {@code event.get/selectHosts} повернув
     * порожній масив (трапляється для тригерів на рівні шаблону). Робить fallback одним
     * викликом {@code trigger.get} для всього пакета.
     */
    private Map<String, String> resolveTriggerHosts(List<String> triggerIds) {
        if (triggerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            JsonObject params = new JsonObject();
            params.add("triggerids", GSON.toJsonTree(triggerIds.toArray(String[]::new)));
            params.add("output", GSON.toJsonTree(new String[]{"triggerid"}));
            params.add("selectHosts", GSON.toJsonTree(new String[]{"host"}));

            JsonObject resp = apiCall("trigger.get", params, authToken);
            JsonArray result = resp.getAsJsonArray("result");
            if (result == null) {
                return Collections.emptyMap();
            }

            Map<String, String> map = new HashMap<>();
            for (JsonElement el : result) {
                JsonObject obj = el.getAsJsonObject();
                String triggerId = obj.get("triggerid").getAsString();
                JsonArray hosts = obj.getAsJsonArray("hosts");
                if (hosts != null && !hosts.isEmpty()) {
                    String host = hosts.get(0).getAsJsonObject().get("host").getAsString();
                    map.put(triggerId, host);
                    log.debug("Zabbix trigger.get fallback: triggerId={} → host={}", triggerId, host);
                }
            }
            return map;
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Zabbix trigger.get fallback помилка: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Один Zabbix-item, з якого цей клієнт уміє читати історію — «Operational status» інтерфейсу
     * або лічильник {@code system.uptime} хоста.
     *
     * @param itemId    ID item у Zabbix
     * @param name      назва item ({@code "Interface 11(...) Operational status"}) або, для
     *                  інтерфейсних items, з уже відрізаним {@link #getInterfaceItems} суфіксом
     *                  {@code " Operational status"}
     * @param valueType Zabbix {@code value_type} (0-4), передається назад у {@code history.get} —
     *                  зчитується з визначення item, а не припускається, бо визначає, у якій
     *                  таблиці історії лежить значення
     */
    public record InterfaceItem(String itemId, String name, int valueType) {
    }

    /**
     * Знаходить усі SNMP-items «Operational status» на хості — по одному на кожен моніторований
     * інтерфейс. Порожній список, якщо таких items у хоста немає (не SNMP-моніторований, або
     * шаблон без інтерфейсних items) — це сигнал для викликача пропустити хост повністю.
     *
     * @param hostname короткий hostname у Zabbix
     * @return інтерфейсні items з відрізаним суфіксом {@code " Operational status"} у назвах;
     *         порожній список при відсутності збігів чи будь-якій помилці API
     */
    public List<InterfaceItem> getInterfaceItems(String hostname) {
        List<InterfaceItem> cached = interfaceItemsCache.get(hostname);
        if (cached != null) {
            return cached;
        }
        String hostId = resolveHostId(hostname);
        if (hostId == null) {
            return Collections.emptyList();
        }
        List<InterfaceItem> items = searchItems(hostId, "Operational status", null).stream()
                .map(i -> new InterfaceItem(i.itemId(),
                        i.name().replaceAll("(?i):?\\s*Operational status.*$", ""),
                        i.valueType()))
                .toList();
        interfaceItemsCache.putIfAbsent(hostname, items);
        return items;
    }

    /**
     * Знаходить item {@code system.uptime} хоста (Template Module Generic SNMPv2: Device
     * uptime) — використовується для факту про зменшення лічильника в аудиті резервного живлення.
     *
     * @param hostname короткий hostname у Zabbix
     * @return item uptime, або порожньо, якщо у хоста його немає чи сталася помилка API
     */
    public Optional<InterfaceItem> getUptimeItem(String hostname) {
        Optional<InterfaceItem> cached = uptimeItemCache.get(hostname);
        if (cached != null) {
            return cached;
        }
        String hostId = resolveHostId(hostname);
        if (hostId == null) {
            return Optional.empty();
        }
        List<InterfaceItem> found = searchItems(hostId, null, "system.uptime");
        Optional<InterfaceItem> item = found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        uptimeItemCache.putIfAbsent(hostname, item);
        return item;
    }

    /**
     * Виконує {@code item.get} для хоста — або за підрядком назви ({@code nameSearch}), або за
     * точним ключем item ({@code keyFilter}); саме один з двох має бути непорожнім.
     */
    private List<InterfaceItem> searchItems(String hostId, String nameSearch, String keyFilter) {
        try {
            JsonObject params = new JsonObject();
            params.add("output", GSON.toJsonTree(new String[]{"itemid", "name", "value_type"}));
            params.add("hostids", GSON.toJsonTree(new String[]{hostId}));
            // status=0 (ITEM_STATUS_ACTIVE): вимкнені items свіжої історії не мають, тож без
            // цього фільтра вони лише роздували б лічильник «немає даних».
            JsonObject filter = new JsonObject();
            filter.addProperty("status", 0);
            if (nameSearch != null) {
                JsonObject search = new JsonObject();
                search.addProperty("name", nameSearch);
                params.add("search", search);
            } else {
                filter.add("key_", GSON.toJsonTree(new String[]{keyFilter}));
            }
            params.add("filter", filter);

            JsonArray result = apiCall("item.get", params, authToken).getAsJsonArray("result");
            if (result == null) {
                return Collections.emptyList();
            }
            List<InterfaceItem> items = new ArrayList<>(result.size());
            for (JsonElement el : result) {
                JsonObject obj = el.getAsJsonObject();
                items.add(new InterfaceItem(
                        obj.get("itemid").getAsString(),
                        obj.get("name").getAsString(),
                        obj.get("value_type").getAsInt()));
            }
            return items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // RuntimeException — нарівні з IOException, як уже робить getProblems: Zabbix може
            // віддати HTTP 200 з HTML-заглушкою reverse-proxy замість JSON, і без цього
            // барʼєра JsonSyntaxException тихо викидав би цілий інцидент зі звіту.
            log.warn("Zabbix item.get(hostId={}, search={}, key={}): {}", hostId, nameSearch, keyFilter, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Один запис історії: саме значення плюс мить, коли Zabbix фактично його зафіксував. Мітка
     * часу тут важлива не менше за значення — знімок може бути набагато старшим за мить самого
     * запиту, якщо на цьому item довкола нічого не змінювалось, і звіт аудиту резервного
     * живлення показує її, щоб читач міг оцінити, наскільки «свіжий» той чи інший відомий стан.
     *
     * @param clock коли це значення зафіксовано
     * @param value зафіксоване значення (стан інтерфейсу, секунди uptime)
     */
    public record HistoryPoint(Instant clock, long value) {
    }

    /**
     * Повертає значення item на або до {@code timestamp} — найновіший запис історії з
     * {@code clock <= timestamp}. Це точковий знімок, а не запит діапазону: відповідає на «що
     * показував цей item у мить T», незалежно від того, як давно до T відбулась остання зміна.
     *
     * @param item      item для читання (несе {@code value_type}, потрібний для вибору правильної
     *                  таблиці історії)
     * @param timestamp unix-час у секундах
     * @return значення разом із власною міткою часу, або порожньо, якщо немає історії на або до цього моменту
     */
    public Optional<HistoryPoint> historyValueBefore(InterfaceItem item, long timestamp) {
        return historyValue(item, "time_till", timestamp, "DESC");
    }

    /**
     * Повертає значення item на або після {@code timestamp} — найраніший запис історії з
     * {@code clock >= timestamp}. Дзеркальний до {@link #historyValueBefore}, дивиться вперед
     * замість назад.
     *
     * @param item      item для читання
     * @param timestamp unix-час у секундах
     * @return значення разом із власною міткою часу, або порожньо, якщо немає історії на або після цього моменту
     */
    public Optional<HistoryPoint> historyValueAfter(InterfaceItem item, long timestamp) {
        return historyValue(item, "time_from", timestamp, "ASC");
    }

    /**
     * Спільна реалізація для {@link #historyValueBefore} та {@link #historyValueAfter}: один
     * запис {@code history.get} з {@code limit=1}, напрямок сортування та часовий фільтр
     * ({@code time_till}/{@code time_from}) задаються викликачем.
     */
    private Optional<HistoryPoint> historyValue(InterfaceItem item, String timeParam, long timestamp, String sortOrder) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("history", item.valueType());
            params.add("itemids", GSON.toJsonTree(new String[]{item.itemId()}));
            params.addProperty(timeParam, timestamp);
            params.addProperty("sortfield", "clock");
            params.addProperty("sortorder", sortOrder);
            params.addProperty("limit", 1);
            params.add("output", GSON.toJsonTree(new String[]{"clock", "value"}));

            JsonArray result = apiCall("history.get", params, authToken).getAsJsonArray("result");
            if (result == null || result.isEmpty()) {
                return Optional.empty();
            }
            JsonObject entry = result.get(0).getAsJsonObject();
            long clock = entry.get("clock").getAsLong();
            String raw = entry.get("value").getAsString();
            // значення історії завжди зберігаються як рядки, незалежно від value_type; парсимо
            // як число (можливо дробове, для float-items) і відкидаємо дробову частину — кожне
            // значення, яке тут читається (стан інтерфейсу, секунди uptime), концептуально ціле.
            return Optional.of(new HistoryPoint(Instant.ofEpochSecond(clock), (long) Double.parseDouble(raw)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // RuntimeException покриває і NumberFormatException, і відсутнє поле «value» в
            // відповіді (NPE), і не-JSON тіло — жодне з них не має коштувати цілого інциденту.
            log.warn("Zabbix history.get(itemId={}, {}={}): {}", item.itemId(), timeParam, timestamp, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Повертає додатковий рядок {@code <tr>} із вбудованим PNG графіка температури для вказаного
     * хоста та опису компонента, або порожній рядок при будь-якій помилці.
     */
    public String getGraphRow(String shortHostname, String desc, LocalDateTime from, LocalDateTime to) {
        return getGraphRowForName(shortHostname, desc + ": Temperature", from, to,
                "getGraphRow(" + shortHostname + ", " + desc + ")");
    }

    /**
     * Повертає додатковий рядок {@code <tr>} із вбудованим PNG графіка Ping для вказаного хоста,
     * або порожній рядок при будь-якій помилці. Графік шукається за стандартною назвою Zabbix
     * {@code "Ping"} (без префікса hostname, який web UI додає лише для відображення).
     */
    public String getPingGraphRow(String hostname, LocalDateTime from, LocalDateTime to) {
        return getGraphRowForName(hostname, "Ping", from, to,
                "getPingGraphRow(" + hostname + ")");
    }

    /**
     * Розвʼязує ID хоста та ID графіка через Zabbix API, завантажує PNG через {@code chart2.php}
     * і повертає рядок {@code <tr>} із зображенням, вбудованим як base64 data URI.
     *
     * @param debugLabel рядок контексту, що використовується у повідомленнях лог/warning
     */
    private String getGraphRowForName(String shortHostname, String graphName, LocalDateTime from, LocalDateTime to, String debugLabel) {
        if (authToken == null) {
            return "";
        }
        try {
            String hostId = resolveHostId(shortHostname);
            if (hostId == null) {
                return "";
            }

            String graphId = resolveGraphId(hostId, graphName);
            if (graphId == null) {
                return "";
            }

            byte[] img = downloadGraph(graphId, from, to);
            if (img == null || img.length == 0) {
                return "";
            }

            int w = config.getZabbixGraphWidth();
            int h = config.getZabbixGraphHeight();
            return "<tr><td></td><td colspan=\"5\">"
                    + "<img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(img) + "\""
                    + " width=\"" + w + "\" height=\"" + h + "\" style=\"display:block;max-width:100%\">"
                    + "</td></tr>\n";
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Zabbix {}: {}", debugLabel, e.getMessage());
            return "";
        }
    }

    /**
     * Розвʼязує короткий hostname у Zabbix {@code hostid} через {@code host.get}, кешуючи
     * результат.
     *
     * <p>Навмисно не {@code computeIfAbsent}: той виконує mapping-функцію під {@code synchronized}
     * на вузлі корзини, тобто монітор утримувався б увесь HTTP round-trip (до 30 с). На Java 21
     * блокування всередині {@code synchronized} ще й пришпилює віртуальний потік до несучого
     * (JEP 491 прибрав це лише в JDK 24) — тобто обмежений fan-out перетворювався б на стільки ж
     * заблокованих платформних потоків. Тут же I/O відбувається поза будь-яким монітором, а ціна
     * — лише можливий повторний запит для того самого ключа, що ідемпотентний.
     */
    private String resolveHostId(String shortName) {
        String cached = hostIdCache.get(shortName);
        if (cached != null) {
            return cached;
        }
        try {
            JsonObject params = new JsonObject();
            params.add("output", GSON.toJsonTree(new String[]{"hostid", "host"}));
            JsonObject filter = new JsonObject();
            filter.add("host", GSON.toJsonTree(new String[]{shortName}));
            params.add("filter", filter);

            JsonArray result = apiCall("host.get", params, authToken).getAsJsonArray("result");
            if (result != null && !result.isEmpty()) {
                String hostId = result.get(0).getAsJsonObject().get("hostid").getAsString();
                log.debug("Zabbix host.get: {} → hostId={}", shortName, hostId);
                hostIdCache.putIfAbsent(shortName, hostId);
                return hostId;
            }
            log.debug("Zabbix host.get: host not found: {}", shortName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.warn("Zabbix host.get({}): {}", shortName, e.getMessage());
        }
        return null;
    }

    /**
     * Розвʼязує назву графіка в Zabbix {@code graphid} для даного хоста через {@code graph.get},
     * кешуючи результат. I/O поза монітором — з тієї ж причини, що й у {@link #resolveHostId}.
     */
    private String resolveGraphId(String hostId, String graphName) {
        String cacheKey = hostId + "\0" + graphName;
        String cached = graphIdCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            JsonObject params = new JsonObject();
            params.add("output", GSON.toJsonTree(new String[]{"graphid", "name"}));
            params.add("hostids", GSON.toJsonTree(new String[]{hostId}));
            JsonObject search = new JsonObject();
            search.addProperty("name", graphName);
            params.add("search", search);

            JsonArray result = apiCall("graph.get", params, authToken).getAsJsonArray("result");
            if (result != null && !result.isEmpty()) {
                String graphId = result.get(0).getAsJsonObject().get("graphid").getAsString();
                String foundName = result.get(0).getAsJsonObject().get("name").getAsString();
                log.debug("Zabbix graph.get: hostId={} search='{}' → graphId={} name='{}'",
                        hostId, graphName, graphId, foundName);
                graphIdCache.putIfAbsent(cacheKey, graphId);
                return graphId;
            }
            log.debug("Zabbix graph.get: no graph found for hostId={} search='{}'", hostId, graphName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.warn("Zabbix graph.get(hostId={}, graphName={}): {}", hostId, graphName, e.getMessage());
        }
        return null;
    }

    /**
     * Завантажує PNG графіка з Zabbix {@code chart2.php}, використовуючи cookie web-сесії.
     * Перед поверненням перевіряє магічні байти PNG; повертає {@code null} при помилках HTTP
     * або відповідях, що не є зображенням.
     */
    private byte[] downloadGraph(String graphId, LocalDateTime from, LocalDateTime to) throws IOException, InterruptedException {
        String url = config.getZabbixUrl() + "/chart2.php"
                + "?graphid=" + graphId
                + "&from=" + URLEncoder.encode(from.format(DT_FORMAT), StandardCharsets.UTF_8)
                + "&to=" + URLEncoder.encode(to.format(DT_FORMAT), StandardCharsets.UTF_8)
                + "&height=" + config.getZabbixGraphHeight()
                + "&width=" + config.getZabbixGraphWidth()
                + "&profileIdx=web.charts.filter";

        log.debug("Zabbix chart2.php GET: {}", url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(GRAPH_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        String contentType = resp.headers().firstValue("Content-Type").orElse("unknown");
        log.debug("Zabbix chart2.php response: HTTP {}, Content-Type: {}, size: {} bytes",
                resp.statusCode(), contentType, resp.body().length);

        if (resp.statusCode() != 200) {
            log.warn("Zabbix chart2.php non-200 status {} for graphId={}", resp.statusCode(), graphId);
            return null;
        }

        if (!contentType.startsWith("image/")) {
            if (log.isDebugEnabled()) {
                byte[] preview = Arrays.copyOf(resp.body(), Math.min(resp.body().length, 300));
                log.debug("Zabbix chart2.php returned non-image ({}): {}", contentType,
                        new String(preview, StandardCharsets.UTF_8).replaceAll("\\s+", " "));
            }
            return null;
        }

        // Перевіряємо магічні байти PNG
        if (resp.body().length < 4
                || resp.body()[0] != (byte) 0x89 || resp.body()[1] != 0x50
                || resp.body()[2] != 0x4e || resp.body()[3] != 0x47) {
            log.warn("Zabbix chart2.php: Content-Type is image but PNG magic missing, graphId={}", graphId);
            return null;
        }

        return resp.body();
    }

    /**
     * Надсилає запит Zabbix JSON-RPC 2.0 і повертає розібраний обʼєкт відповіді.
     *
     * @throws IOException якщо HTTP-запит не вдався, або сервер повернув статус, відмінний від 200
     */
    private JsonObject apiCall(String method, JsonObject params, String auth) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("method", method);
        body.add("params", params);
        body.add("auth", auth != null ? GSON.toJsonTree(auth) : JsonNull.INSTANCE);
        body.addProperty("id", ID_GEN.getAndIncrement());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getZabbixApi()))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Zabbix API HTTP " + resp.statusCode() + " for method " + method);
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
