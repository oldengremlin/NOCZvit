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
package net.ukrcom.noczvit;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ZabbixClient {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicInteger ID_GEN = new AtomicInteger(1);
    private static final Gson GSON = new Gson();

    private final Config config;
    private final HttpClient http;
    private volatile String authToken;

    private final ConcurrentHashMap<String, String> hostIdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> graphIdCache = new ConcurrentHashMap<>();

    public ZabbixClient(Config config) {
        this.config = config;
        // CookieManager stores the zbx_sessionid from web login and sends it automatically
        // with every subsequent request (including chart2.php).
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // Performs both API login (for host.get / graph.get) and web login (for chart2.php).
    public boolean login() {
        boolean apiOk = apiLogin();
        boolean webOk = webLogin();
        log.debug("Zabbix login: apiOk={}, webOk={}", apiOk, webOk);
        return apiOk && webOk;
    }

    private boolean apiLogin() {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("user", config.getZabbixUsername());
            params.addProperty("password", config.getZabbixPassword());
            JsonObject resp = apiCall("user.login", params, null);
            JsonElement result = resp.get("result");
            if (result != null && result.isJsonPrimitive()) {
                authToken = result.getAsString();
                log.debug("Zabbix API login OK, token={}...", authToken.substring(0, 8));
                return true;
            }
            log.warn("Zabbix API login failed: {}", resp);
        } catch (IOException | InterruptedException e) {
            log.warn("Zabbix API login error: {}", e.getMessage());
        }
        return false;
    }

    // Web login via POST to index.php — Zabbix sets zbx_sessionid cookie,
    // which is stored in CookieManager and sent automatically with chart2.php requests.
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
                cm.getCookieStore().getCookies().forEach(c ->
                        log.debug("  cookie: {}={}...", c.getName(),
                                c.getValue().substring(0, Math.min(8, c.getValue().length()))));
            }

            return hasCookie;
        } catch (IOException | InterruptedException e) {
            log.warn("Zabbix web login error: {}", e.getMessage());
            return false;
        }
    }

    // Returns extra <tr> row with embedded temperature graph PNG, or "" on any failure.
    public String getGraphRow(String shortHostname, String desc, LocalDateTime from, LocalDateTime to) {
        return getGraphRowForName(shortHostname, desc + ": Temperature", from, to,
                "getGraphRow(" + shortHostname + ", " + desc + ")");
    }

    // Returns extra <tr> row with embedded Ping graph PNG, or "" on any failure.
    // Graph name is "Ping" (standard Zabbix template name, without hostname prefix —
    // the web UI prepends "hostname: " for display but the API name is just "Ping").
    public String getPingGraphRow(String hostname, LocalDateTime from, LocalDateTime to) {
        return getGraphRowForName(hostname, "Ping", from, to,
                "getPingGraphRow(" + hostname + ")");
    }

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
            return "<tr><td></td><td colspan=\"3\">"
                    + "<img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(img) + "\""
                    + " width=\"" + w + "\" height=\"" + h + "\" style=\"display:block;max-width:100%\">"
                    + "</td></tr>\n";
        } catch (IOException | InterruptedException e) {
            log.warn("Zabbix {}: {}", debugLabel, e.getMessage());
            return "";
        }
    }

    private String resolveHostId(String shortName) {
        return hostIdCache.computeIfAbsent(shortName, (var k) -> {
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
                    return hostId;
                }
                log.debug("Zabbix host.get: host not found: {}", shortName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.warn("Zabbix host.get({}): {}", shortName, e.getMessage());
            }
            return null;
        });
    }

    private String resolveGraphId(String hostId, String graphName) {
        String cacheKey = hostId + "\0" + graphName;
        return graphIdCache.computeIfAbsent(cacheKey, k -> {
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
                    return graphId;
                }
                log.debug("Zabbix graph.get: no graph found for hostId={} search='{}'", hostId, graphName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.warn("Zabbix graph.get(hostId={}, graphName={}): {}", hostId, graphName, e.getMessage());
            }
            return null;
        });
    }

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

        // Verify PNG magic bytes
        if (resp.body().length < 4
                || resp.body()[0] != (byte) 0x89 || resp.body()[1] != 0x50
                || resp.body()[2] != 0x4e || resp.body()[3] != 0x47) {
            log.warn("Zabbix chart2.php: Content-Type is image but PNG magic missing, graphId={}", graphId);
            return null;
        }

        return resp.body();
    }

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
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Zabbix API HTTP " + resp.statusCode() + " for method " + method);
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
