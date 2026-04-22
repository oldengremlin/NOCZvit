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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
                Map<Integer, Map<String, String>> accountMap = buildAccountMap();
                for (String debtor : fetchDebtors(accountMap)) {
                    returnMessage.append("<li style=\"margin-left: 50px;\">")
                            .append(StringEscapeUtils.escapeHtml4(debtor))
                            .append("</li>");
                }
            } catch (SQLException e) {
                if (config.isDebug()) {
                    System.err.println("Debtors DB error: " + e.getMessage());
                }
            }
        }

        returnMessage.append("</ol><p>");
    }

    private Connection connectTo(String server, String database, String user, String password) throws SQLException {
        String url = "jdbc:sqlserver://" + server + ";databaseName=" + database;
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("encrypt", "false");
        props.setProperty("trustServerCertificate", "true");
        return DriverManager.getConnection(url, props);
    }

    private Map<Integer, Map<String, String>> buildAccountMap() throws SQLException {
        Map<Integer, Map<String, String>> accountMap = new HashMap<>();
        try (Connection conn = connectTo(
                config.getAccountMssqlServer(), config.getAccountMssqlDatabase(),
                config.getAccountMssqlUser(), config.getAccountMssqlPassword()); PreparedStatement stmt = conn.prepareStatement(
             "SELECT Customer_id, FirmId, Title FROM [dbo].[Customers]"); ResultSet rs = stmt.executeQuery()) {
            System.out.println("buildAccountMap");
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
    // After splitting by "," gives alternating {"Key":N and "Value":M} fragments
    private List<String> parseServicesLastState(String paramValue, Map<Integer, Map<String, String>> accountMap) {
        List<String> result = new ArrayList<>();
        if (paramValue == null || paramValue.length() < 2) {
            return result;
        }
        if (paramValue.startsWith("[") && paramValue.endsWith("]")) {
            paramValue = paramValue.substring(1, paramValue.length() - 1);
        }

        String currentFirmId = null;
        for (String item : paramValue.split(",")) {
            item = item.trim();
            if (item.startsWith("{\"Key\":")) {
                currentFirmId = item.substring("{\"Key\":".length()).replaceAll("\\}$", "").trim();
            } else if (item.startsWith("\"Value\":")) {
                String customerIdStr = item.substring("\"Value\":".length()).replaceAll("\\}$", "").trim();
                if (currentFirmId != null) {
                    try {
                        int customerId = Integer.parseInt(customerIdStr);
                        Map<String, String> firmMap = accountMap.get(customerId);
                        if (firmMap != null) {
                            String title = firmMap.get(currentFirmId);
                            if (title != null) {
                                result.add(customerId + ", " + title);
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                currentFirmId = null;
            }
        }
        return result;
    }
}
