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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Builds the "temporarily blocked subscribers" HTML section by querying two MSSQL databases:
 * the account DB for customer name resolution, and the accequipment DB for the current blocked
 * list ({@code ServicesLastState} JSON parameter).
 *
 * <p>Database connection parameters are resolved through FreeTDS {@code freetds.conf} aliases
 * (searched in {@code ~/.freetds.conf}, {@code /etc/freetds/freetds.conf}, and
 * {@code /etc/freetds.conf}) with a fallback to direct host:1433.
 */
@Slf4j
public class Debtors {

    private final StringBuilder returnMessage;
    private final Config config;

    /**
     * Constructs the HTML section immediately. The result is available via {@link #toString()}.
     */
    public Debtors(Config config) {
        this.config = config;
        this.returnMessage = new StringBuilder();
        getDebtors();
    }

    @Override
    public String toString() {
        return returnMessage.toString();
    }

    /** Queries the databases and appends the subscriber rows to {@code returnMessage}. */
    private void getDebtors() {
        returnMessage.append("<p>\n<h1>Список тимчасово заблокованих абонентів</h1>\n")
                .append("<table class=\"table-debtors\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                .append("<thead><tr>")
                .append("<th style=\"width:30px\">№</th>")
                .append("<th>Абонент</th>")
                .append("</tr></thead><tbody>\n");

        if (config.isDebtorsEnabled()) {
            try {
//
//              ІМПЕРАТИВНИЙ СТИЛЬ
//
                /*
                int n = 0;
                Map<Integer, Map<String, String>> accountMap = buildAccountMap();
                for (String debtor : fetchDebtors(accountMap)) {
                    returnMessage.append("<tr><td>").append(++n).append(".</td>")
                            .append("<td>").append(StringEscapeUtils.escapeHtml4(debtor)).append("</td></tr>\n");
                }
                 */
//
//              ФУНКЦІОНАЛЬНИЙ СТИЛЬ
//
                AtomicInteger n = new AtomicInteger(0);
                String debtorsHtml = fetchDebtors(buildAccountMap()).stream()
                        .map(debtor -> "<tr><td>" + n.incrementAndGet() + ".</td>"
                        + "<td>" + StringEscapeUtils.escapeHtml4(debtor) + "</td></tr>\n")
                        .collect(Collectors.joining());
                returnMessage.append(debtorsHtml);

            } catch (SQLException e) {
                log.error("Debtors DB error: {}", e.getMessage());
            }
        }

        returnMessage.append("</tbody></table>\n");
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

    /**
     * Parses a single {@code freetds.conf} file for the given server alias.
     *
     * @return {@code [host, port]} if the section is found, {@code null} otherwise
     */
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

    /**
     * Opens a jTDS MSSQL connection, resolving {@code server} through FreeTDS aliases first.
     */
    private Connection connectTo(String server, String database, String user, String password) throws SQLException {
        String[] hostPort = resolveServer(server);
        String url = "jdbc:jtds:sqlserver://" + hostPort[0] + ":" + hostPort[1] + "/" + database;
        log.debug("Debtors connecting: {}", url);
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Loads {@code Customer_id → FirmId → Title} from the account DB's {@code Customers} table.
     */
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

    /**
     * Reads the latest {@code ServicesLastState} JSON blob from the accequipment DB and
     * resolves each entry to a subscriber name using {@code accountMap}.
     */
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

    /**
     * Parses the {@code ServicesLastState} JSON array ({@code [{"Key":firmId,"Value":customerId},...]}
     * ) into a list of {@code "customerId, Title"} strings, resolved via {@code accountMap}.
     */
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
            log.warn("Debtors JSON parse error: {}", e.getMessage());
        }
        return result;
    }
}
