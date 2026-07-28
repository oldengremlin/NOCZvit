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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public boolean login() {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("user", config.getZabbixUsername());
            params.addProperty("password", config.getZabbixPassword());
            JsonObject resp = apiCall("user.login", params, null);
            JsonElement result = resp.get("result");
            if (result != null && result.isJsonPrimitive()) {
                authToken = result.getAsString();
                return true;
            }
            if (config.isDebug() && resp.has("error")) {
                System.err.println("Zabbix login error: " + resp.get("error"));
            }
        } catch (Exception e) {
            if (config.isDebug()) {
                System.err.println("Zabbix login failed: " + e.getMessage());
            }
        }
        return false;
    }

    // Returns extra <tr> row with embedded graph PNG, or "" on any failure.
    public String getGraphRow(String shortHostname, String desc, LocalDateTime from, LocalDateTime to) {
        if (authToken == null) {
            return "";
        }
        try {
            String hostId = resolveHostId(shortHostname);
            if (hostId == null) return "";

            String graphId = resolveGraphId(hostId, desc);
            if (graphId == null) return "";

            byte[] img = downloadGraph(graphId, from, to);
            if (img == null || img.length == 0) return "";

            int w = config.getZabbixGraphWidth();
            int h = config.getZabbixGraphHeight();
            return "<tr><td></td><td colspan=\"3\">"
                    + "<img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(img) + "\""
                    + " width=\"" + w + "\" height=\"" + h + "\" style=\"display:block;max-width:100%\">"
                    + "</td></tr>\n";
        } catch (Exception e) {
            if (config.isDebug()) {
                System.err.println("Zabbix getGraphRow(" + shortHostname + ", " + desc + "): " + e.getMessage());
            }
            return "";
        }
    }

    private String resolveHostId(String shortName) {
        return hostIdCache.computeIfAbsent(shortName, k -> {
            try {
                JsonObject params = new JsonObject();
                params.add("output", GSON.toJsonTree(new String[]{"hostid", "host"}));
                JsonObject filter = new JsonObject();
                filter.add("host", GSON.toJsonTree(new String[]{shortName}));
                params.add("filter", filter);

                JsonArray result = apiCall("host.get", params, authToken).getAsJsonArray("result");
                if (result != null && result.size() > 0) {
                    return result.get(0).getAsJsonObject().get("hostid").getAsString();
                }
                if (config.isDebug()) {
                    System.err.println("Zabbix: host not found: " + shortName);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (config.isDebug()) {
                    System.err.println("Zabbix host.get(" + shortName + "): " + e.getMessage());
                }
            }
            return null;
        });
    }

    private String resolveGraphId(String hostId, String desc) {
        String cacheKey = hostId + "\0" + desc;
        return graphIdCache.computeIfAbsent(cacheKey, k -> {
            try {
                JsonObject params = new JsonObject();
                params.add("output", GSON.toJsonTree(new String[]{"graphid", "name"}));
                params.add("hostids", GSON.toJsonTree(new String[]{hostId}));
                JsonObject search = new JsonObject();
                search.addProperty("name", desc + ": Temperature");
                params.add("search", search);

                JsonArray result = apiCall("graph.get", params, authToken).getAsJsonArray("result");
                if (result != null && result.size() > 0) {
                    return result.get(0).getAsJsonObject().get("graphid").getAsString();
                }
                if (config.isDebug()) {
                    System.err.println("Zabbix: graph not found for hostId=" + hostId + " desc=" + desc);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (config.isDebug()) {
                    System.err.println("Zabbix graph.get(hostId=" + hostId + ", desc=" + desc + "): " + e.getMessage());
                }
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

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cookie", "zbx_sessionid=" + authToken)
                .GET()
                .build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            if (config.isDebug()) {
                System.err.println("Zabbix chart2.php HTTP " + resp.statusCode() + " for graphId=" + graphId);
            }
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
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
