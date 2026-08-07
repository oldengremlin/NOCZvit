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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * Формує HTML-секцію "Список тимчасово заблокованих абонентів", опитуючи дві бази MSSQL:
 * account DB для визначення імен клієнтів та accequipment DB для поточного списку
 * заблокованих (JSON-параметр {@code ServicesLastState}).
 *
 * <p>Параметри підключення до баз даних визначаються через алiаси FreeTDS {@code freetds.conf}
 * (пошук у {@code ~/.freetds.conf}, {@code /etc/freetds/freetds.conf} та
 * {@code /etc/freetds.conf}) з відкатом на прямий host:1433.
 */
@Slf4j
public class Debtors {

    private final StringBuilder returnMessage;
    private final Config config;

    /**
     * Одразу формує HTML-секцію. Результат доступний через {@link #toString()}.
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

    /** Опитує бази даних і додає рядки абонентів до {@code returnMessage}. */
    private void getDebtors() {
        returnMessage.append("<p>\n<h1>Список тимчасово заблокованих абонентів</h1>\n")
                .append("<table class=\"table-debtors\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                .append("<thead><tr>")
                .append("<th style=\"width:30px\">№</th>")
                .append("<th>Абонент</th>")
                .append("</tr></thead><tbody>\n");

        if (config.isDebtorsEnabled()) {
            try {
                // AtomicInteger замість звичайного int — лямбді потрібна effectively final
                // змінна для наскрізної нумерації рядків під час формування HTML-таблиці
                AtomicInteger n = new AtomicInteger(0);
                returnMessage.append(fetchDebtors().stream()
                        .map(debtor -> "<tr><td>" + n.incrementAndGet() + ".</td>"
                        + "<td>" + StringEscapeUtils.escapeHtml4(debtor) + "</td></tr>\n")
                        .collect(Collectors.joining()));

            } catch (SQLException e) {
                log.error("Debtors DB error: {}", e.getMessage());
            }
        }

        returnMessage.append("</tbody></table>\n");
    }

    // Визначає аліас сервера FreeTDS із freetds.conf у [host, port].
    // Якщо не знайдено — відкат на [serverName, "1433"].
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
     * Розбирає один файл {@code freetds.conf} у пошуках заданого аліасу сервера.
     *
     * @return {@code [host, port]}, якщо секцію знайдено, інакше {@code null}
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
     * Відкриває з'єднання jTDS MSSQL, спершу визначаючи {@code server} через аліаси FreeTDS.
     */
    private Connection connectTo(String server, String database, String user, String password) throws SQLException {
        String[] hostPort = resolveServer(server);
        // jTDS за замовчуванням обидва таймаути ставить у 0 (= чекати вічно); інакше
        // заблокований фаєрволом MSSQL-хост зависав би cron-запуск нескінченно.
        String url = "jdbc:jtds:sqlserver://" + hostPort[0] + ":" + hostPort[1] + "/" + database
                + ";loginTimeout=10;socketTimeout=60";
        log.debug("Debtors connecting: {}", url);
        return DriverManager.getConnection(url, user, password);
    }

    /** Один запис масиву JSON {@code ServicesLastState}. */
    private record ServiceEntry(String firmId, int customerId) {
    }

    /** MSSQL обмежує запит 2100 параметрами; тримаємось значно нижче при побудові списку IN. */
    private static final int ID_BATCH = 1000;

    /**
     * Завантажує {@code Customer_id → FirmId → Title} лише для заданих ID клієнтів.
     *
     * <p>Список заблокованих абонентів містить десятки записів, тоді як {@code Customers} —
     * десятки тисяч рядків, тому ID визначаються фільтрованим запитом замість завантаження
     * всієї таблиці у вкладену мапу. Дві бази даних розташовані на різних серверах, саме тому
     * порядок читання інвертовано: спершу набір ID береться з accequipment DB.
     */
    private Map<Integer, Map<String, String>> buildAccountMap(List<Integer> customerIds) throws SQLException {
        Map<Integer, Map<String, String>> accountMap = new HashMap<>();
        if (customerIds.isEmpty()) {
            return accountMap;
        }
        try (Connection conn = connectTo(
                config.getAccountMssqlServer(), config.getAccountMssqlDatabase(),
                config.getAccountMssqlUser(), config.getAccountMssqlPassword())) {
            for (int off = 0; off < customerIds.size(); off += ID_BATCH) {
                List<Integer> batch = customerIds.subList(off, Math.min(off + ID_BATCH, customerIds.size()));
                // плейсхолдери генеруються за розміром пакета, значення завжди прив'язуються
                String placeholders = batch.stream().map(id -> "?").collect(Collectors.joining(","));
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT Customer_id, FirmId, Title FROM [dbo].[Customers]"
                        + " WHERE Customer_id IN (" + placeholders + ")")) {
                    for (int i = 0; i < batch.size(); i++) {
                        stmt.setInt(i + 1, batch.get(i));
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            accountMap.computeIfAbsent(rs.getInt("Customer_id"), k -> new HashMap<>())
                                    .putIfAbsent(rs.getString("FirmId"), rs.getString("Title"));
                        }
                    }
                }
            }
        }
        return accountMap;
    }

    /**
     * Читає останній blob {@code ServicesLastState} з accequipment DB, після чого визначає
     * імена абонентів для відповідних ID клієнтів через account DB.
     */
    private List<String> fetchDebtors() throws SQLException {
        List<ServiceEntry> entries = readServicesLastState();
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<Integer, Map<String, String>> accountMap = buildAccountMap(
                entries.stream().map(ServiceEntry::customerId).distinct().toList());

        List<String> result = new ArrayList<>();
        for (ServiceEntry entry : entries) {
            Map<String, String> firmMap = accountMap.get(entry.customerId());
            if (firmMap != null) {
                String title = firmMap.get(entry.firmId());
                if (title != null) {
                    result.add(entry.customerId() + ", " + title);
                }
            }
        }
        return result;
    }

    /** Читає найновіше значення параметра {@code ServicesLastState} і розбирає його. */
    private List<ServiceEntry> readServicesLastState() throws SQLException {
        try (Connection conn = connectTo(
                config.getAccequipmentMssqlServer(), config.getAccequipmentMssqlDatabase(),
                config.getAccequipmentMssqlUser(), config.getAccequipmentMssqlPassword()); PreparedStatement stmt = conn.prepareStatement(
             "SELECT TOP 1 [ParamValue] FROM [dbo].[AccEqu.Parameters]"
             + " WHERE [ParamName] = ? ORDER BY [ParamDate] DESC")) {
            stmt.setString(1, "ServicesLastState");
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? parseServicesLastState(rs.getString(1)) : List.of();
            }
        }
    }

    /**
     * Розбирає JSON-масив {@code ServicesLastState}
     * ({@code [{"Key":firmId,"Value":customerId},...]}) у записи {@link ServiceEntry}.
     */
    private List<ServiceEntry> parseServicesLastState(String paramValue) {
        List<ServiceEntry> result = new ArrayList<>();
        if (paramValue == null || paramValue.isBlank()) {
            return result;
        }
        try {
            JsonElement root = JsonParser.parseString(paramValue);
            if (!root.isJsonArray()) {
                log.warn("Debtors: ServicesLastState is not a JSON array, skipping");
                return result;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                JsonElement key = obj.get("Key");
                JsonElement value = obj.get("Value");
                if (key == null || value == null
                        || !key.isJsonPrimitive() || !value.isJsonPrimitive()) {
                    continue;
                }
                result.add(new ServiceEntry(key.getAsString(), value.getAsInt()));
            }
            // Раніше один пошкоджений рядок ServicesLastState зривав увесь звіт:
            // getAsJsonArray/getAsInt кидають IllegalState/NumberFormat/UnsupportedOperation,
            // жоден з яких не покривається JsonSyntaxException, і помилка доходила до exit(1).
        } catch (RuntimeException e) {
            log.warn("Debtors JSON parse error: {}", e.toString());
        }
        return result;
    }
}
