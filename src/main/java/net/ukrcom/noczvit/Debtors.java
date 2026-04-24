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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.text.StringEscapeUtils;

public class Debtors {

    private final StringBuilder returnMessage;
    private final Config config;

    public Debtors(Config config) {
        this.config = config;
        this.returnMessage = new StringBuilder();
        getDebtors();
    }

    @Override
    public String toString() {
        return returnMessage.toString();
    }

    private void getDebtors() {
        returnMessage.append("<p><ol><h1><small><small>Список тимчасово заблокованих абонентів</small></small></h1>");

        if (config.isDebtorsEnabled()) {
            try {
//
//              ІМПЕРАТИВНИЙ СТИЛЬ
//
                /*
                Map<Integer, Map<String, String>> accountMap = buildAccountMap();
                for (String debtor : fetchDebtors(accountMap)) {
                    returnMessage.append("<li style=\"margin-left: 50px;\">")
                            .append(StringEscapeUtils.escapeHtml4(debtor))
                            .append("</li>");
                }
                 */
//
//              АНТИПАТЕРН ФУНКЦІОНАЛЬНОГО СТИЛЮ (АЛЕ ТАКОЖ ПРАЦЮЄ)
//
                /*
                fetchDebtors(buildAccountMap()).stream()
                        .forEach(
                                debtor -> returnMessage.append("<li style=\"margin-left: 50px;\">")
                                        .append(StringEscapeUtils.escapeHtml4(debtor))
                                        .append("</li>")
                        );
                 */
//
//              ФУНКЦІОНАЛЬНИЙ СТИЛЬ
//
                String debtorsHtml = fetchDebtors(buildAccountMap()).stream()
                        .map(
                                debtor -> "<li style=\"margin-left: 50px;\">"
                                        .concat(StringEscapeUtils.escapeHtml4(debtor))
                                        .concat("</li>")
                        )
                        .collect(Collectors.joining());
                returnMessage.append(debtorsHtml);

            } catch (SQLException e) {
                if (config.isDebug()) {
                    System.err.println("Debtors DB error: " + e.getMessage());
                }
            }
        }

        returnMessage.append("</ol><p>");
    }

    // Resolves FreeTDS server alias from freetds.conf to [host, port].
    // Falls back to [serverName, "1433"] if not found.
    private String[] resolveServer(String serverName) {
        String[] searchPaths = {
            System.getProperty("user.home") + "/.freetds.conf",
            "/etc/freetds/freetds.conf",
            "/etc/freetds.conf"
        };
        for (String path : searchPaths) {
            String[] result = parseFreeTdsConf(path, serverName);
            if (result != null) {
                return result;
            }
        }
        return new String[]{serverName, "1433"};
    }

    private String[] parseFreeTdsConf(String path, String serverName) {
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            boolean inSection = false;
            String host = null;
            String port = "1433";
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (inSection && host != null) {
                        return new String[]{host, port};
                    }
                    inSection = line.equals("[" + serverName + "]");
                    host = null;
                    port = "1433";
                } else if (inSection && line.contains("=")) {
                    String[] kv = line.split("=", 2);
                    switch (kv[0].trim()) {
                        case "host" ->
                            host = kv[1].trim();
                        case "port" ->
                            port = kv[1].trim();
                    }
                }
            }
            if (inSection && host != null) {
                return new String[]{host, port};
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private Connection connectTo(String server, String database, String user, String password) throws SQLException {
        String[] hostPort = resolveServer(server);
        String url = "jdbc:jtds:sqlserver://" + hostPort[0] + ":" + hostPort[1] + "/" + database;
        if (config.isDebug()) {
            System.err.println("Debtors connecting: " + url);
        }
        return DriverManager.getConnection(url, user, password);
    }

    private Map<Integer, Map<String, String>> buildAccountMap() throws SQLException {
        Map<Integer, Map<String, String>> accountMap = new HashMap<>();
        try (Connection conn = connectTo(
                config.getAccountMssqlServer(), config.getAccountMssqlDatabase(),
                config.getAccountMssqlUser(), config.getAccountMssqlPassword()); PreparedStatement stmt = conn.prepareStatement(
             "SELECT Customer_id, FirmId, Title FROM [dbo].[Customers]"); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                int customerId = rs.getInt("Customer_id");
                String firmId = rs.getString("FirmId");
                String title = rs.getString("Title");
                accountMap.computeIfAbsent(customerId, k -> new HashMap<>())
                        .putIfAbsent(firmId, title);
            }
        }
        return accountMap;
    }

    private List<String> fetchDebtors(Map<Integer, Map<String, String>> accountMap) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection conn = connectTo(
                config.getAccequipmentMssqlServer(), config.getAccequipmentMssqlDatabase(),
                config.getAccequipmentMssqlUser(), config.getAccequipmentMssqlPassword()); PreparedStatement stmt = conn.prepareStatement(
             "SELECT TOP 1 [ParamValue] FROM [dbo].[AccEqu.Parameters]"
             + " WHERE [ParamName] = ? ORDER BY [ParamDate] DESC")) {
            stmt.setString(1, "ServicesLastState");
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    result = parseServicesLastState(rs.getString(1), accountMap);
                }
            }
        }
        return result;
    }

    // ServicesLastState format: [{"Key":firmId,"Value":customerId},...]
    private List<String> parseServicesLastState(String paramValue, Map<Integer, Map<String, String>> accountMap) {
        List<String> result = new ArrayList<>();
        if (paramValue == null || paramValue.isBlank()) {
            return result;
        }
        try {
            JsonArray array = JsonParser.parseString(paramValue).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                String firmId = obj.get("Key").getAsString();
                int customerId = obj.get("Value").getAsInt();
                Map<String, String> firmMap = accountMap.get(customerId);
                if (firmMap != null) {
                    String title = firmMap.get(firmId);
                    if (title != null) {
                        result.add(customerId + ", " + title);
                    }
                }
            }
        } catch (JsonSyntaxException e) {
            if (config.isDebug()) {
                System.err.println("Debtors JSON parse error: " + e.getMessage());
            }
        }
        return result;
    }
}
