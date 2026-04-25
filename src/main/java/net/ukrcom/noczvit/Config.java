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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.Getter;
import lombok.AccessLevel;

@Getter
public class Config {

    @Getter(AccessLevel.NONE)
    private final Properties properties;
    private final Map<String, Map<String, String>> hosts;
    private final Map<String, Map<String, String>> ramos;
    private boolean debug;
    private boolean incidentsEnabled;
    private boolean temperatureEnabled;
    private boolean ramosEnabled;
    @Getter(AccessLevel.NONE)
    private String configPath;
    private String dictionaryPdPath;
    private String dictionarySdhPath;
    private static final String HELP_PATH = "help.txt";

    private String accountMssqlUser;
    private String accountMssqlPassword;
    private String accountMssqlServer;
    private String accountMssqlDatabase;
    private String accequipmentMssqlUser;
    private String accequipmentMssqlPassword;
    private String accequipmentMssqlServer;
    private String accequipmentMssqlDatabase;

    public Config(String[] args) throws IOException {
        properties = new Properties();
        hosts = new HashMap<>();
        ramos = new HashMap<>();
        configPath = null;
        dictionaryPdPath = null;
        dictionarySdhPath = null;
        parseArgs(args);
        loadProperties();
        debug = Boolean.parseBoolean(properties.getProperty("debug", "false"));
        incidentsEnabled = Boolean.parseBoolean(properties.getProperty("incidents", "true"));
        temperatureEnabled = Boolean.parseBoolean(properties.getProperty("temperature", "true"));
        ramosEnabled = Boolean.parseBoolean(properties.getProperty("ramos", "false"));
        parseArgs(args);
        parseHosts();
        parseRamos();

        accountMssqlUser = properties.getProperty("account-mssql-user", "");
        accountMssqlPassword = properties.getProperty("account-mssql-password", "");
        accountMssqlServer = properties.getProperty("account-mssql-server", "");
        accountMssqlDatabase = properties.getProperty("account-mssql-database", "");
        accequipmentMssqlUser = properties.getProperty("accequipment-mssql-user", "");
        accequipmentMssqlPassword = properties.getProperty("accequipment-mssql-password", "");
        accequipmentMssqlServer = properties.getProperty("accequipment-mssql-server", "");
        accequipmentMssqlDatabase = properties.getProperty("accequipment-mssql-database", "");
    }

    private void loadProperties() throws IOException {
        if (configPath != null) {
            try (InputStream input = new FileInputStream(configPath)) {
                properties.load(input);
            } catch (IOException e) {
                throw new IOException("Failed to load configuration file: " + configPath, e);
            }
        } else {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("noczvit.properties")) {
                if (input == null) {
                    throw new IOException("Default noczvit.properties not found in resources");
                }
                properties.load(input);
            }
        }
    }

    private void parseArgs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length()).trim();
            } else if (arg.startsWith("--dictionarypd=")) {
                dictionaryPdPath = arg.substring("--dictionarypd=".length()).trim();
            } else if (arg.startsWith("--dictionarysdh=")) {
                dictionarySdhPath = arg.substring("--dictionarysdh=".length()).trim();
            } else {
                switch (arg) {
                    case "--incidents" ->
                        incidentsEnabled = true;
                    case "--no-incidents" ->
                        incidentsEnabled = false;
                    case "--temperature" ->
                        temperatureEnabled = true;
                    case "--no-temperature" ->
                        temperatureEnabled = false;
                    case "--ramos" ->
                        ramosEnabled = true;
                    case "--no-ramos" ->
                        ramosEnabled = false;
                    case "--debug" ->
                        debug = true;
                    case "--no-debug" ->
                        debug = false;
                    default -> {
                        printHelp();
                        System.err.println("Error: Unknown argument: " + arg);
                        System.exit(1);
                    }
                }
            }
        }
    }

    private void printHelp() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(HELP_PATH)) {
            if (input == null) {
                System.err.println("Error: Default " + HELP_PATH + " not found in resources");
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                }
            }
        } catch (IOException e) {
            System.err.println("Error: Failed to read " + HELP_PATH + ": " + e.getMessage());
        }
    }

    private void parseHosts() {
        String hostsStr = properties.getProperty("snmp.hosts");
        if (hostsStr != null) {
            for (String hostEntry : hostsStr.split(",")) {
                String[] parts = hostEntry.split(":", 2);
                if (parts.length > 1) {
                    String hostName = parts[0].trim();
                    Map<String, String> hostData = new HashMap<>();
                    for (String attr : parts[1].split(";")) {
                        String[] kv = attr.split("=", 2);
                        if (kv.length > 1) {
                            hostData.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                    hosts.put(hostName, hostData);
                }
            }
        }
    }

    private void parseRamos() {
        String ramosStr = properties.getProperty("snmp.ramos");
        if (ramosStr != null) {
            for (String ramosEntry : ramosStr.split(",")) {
                String[] parts = ramosEntry.split(":", 2);
                if (parts.length > 1) {
                    String ip = parts[0].trim();
                    Map<String, String> ramosData = new HashMap<>();
                    for (String attr : parts[1].split(";")) {
                        String[] kv = attr.split("=", 2);
                        if (kv.length > 1) {
                            ramosData.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                    ramos.put(ip, ramosData);
                }
            }
        }
    }

    public String getMailHostname() {
        return properties.getProperty("mail.hostname");
    }

    public String getMailUsername() {
        return properties.getProperty("mail.username");
    }

    public String getMailPassword() {
        return properties.getProperty("mail.password");
    }

    public boolean isMailSsl() {
        return Boolean.parseBoolean(properties.getProperty("mail.ssl", "false"));
    }

    public String getZabbixFolder() {
        return properties.getProperty("mail.zabbixFolder");
    }

    public String getJnxOperatingDescr() {
        return properties.getProperty("snmp.jnxOperatingDescr");
    }

    public String getJnxOperatingTemp() {
        return properties.getProperty("snmp.jnxOperatingTemp");
    }

    public String getSnmpCommunity() {
        return properties.getProperty("snmp.community", "public");
    }

    public String getSnmpCommunityCelsius() {
        return properties.getProperty("snmp.community.celsius", getSnmpCommunity());
    }

    public String getSnmpCommunityRamos() {
        return properties.getProperty("snmp.community.ramos", getSnmpCommunity());
    }

    public String getSnmpHostsSuffix() {
        return properties.getProperty("snmp.hosts.suffix", "");
    }

    public String getEmailFrom() {
        return properties.getProperty("email.from");
    }

    public String getEmailReplyTo() {
        return properties.getProperty("email.replyTo");
    }

    public List<String> getEmailTo() {
        List<String> toList = new ArrayList<>();
        String toStr = properties.getProperty("email.to");
        if (toStr != null) {
            for (String email : toStr.split(",")) {
                toList.add(email.trim());
            }
        }
        return toList;
    }

    public String getEmailToDebug() {
        return properties.getProperty("email.toDebug");
    }

    public boolean isDebtorsEnabled() {
        return !accountMssqlServer.isEmpty() && !accountMssqlDatabase.isEmpty()
                && !accequipmentMssqlServer.isEmpty() && !accequipmentMssqlDatabase.isEmpty();
    }

    public boolean isValid() {
        boolean isEmailValid = getEmailFrom() != null && getEmailReplyTo() != null && !getEmailTo().isEmpty();
        boolean isSnmpValid = true;
        if (isTemperatureEnabled() || isRamosEnabled()) {
            isSnmpValid = getSnmpCommunity() != null || getSnmpCommunityCelsius() != null || getSnmpCommunityRamos() != null;
        }
        return isEmailValid && isSnmpValid;
    }
}
