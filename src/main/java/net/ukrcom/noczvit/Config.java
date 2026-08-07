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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.Getter;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * Application configuration loaded from a properties file and overridden by CLI arguments.
 *
 * <p>Construction sequence (all called from the constructor in order):
 * {@link #initValues()} → {@link #parsePathArgs(String[])} → {@link #loadProperties()} →
 * {@link #generalProperties()} → {@link #parseFlagArgs(String[])} →
 * {@link #hostsProperties()} → {@link #ramosProperties()} → {@link #celsiusProperties()} →
 * {@link #emailProperties()} → {@link #mssqlProperties()} → {@link #zabbixProperties()} →
 * {@link #claudeProperties()} → {@link #historyResumeProperties()} → {@link #trapProperties()}.
 */
@Slf4j
// secrets are excluded so that any future log.debug("config={}", config) cannot leak them;
// `properties` holds every key-value pair, i.e. all secrets a second time
@ToString(includeFieldNames = true, exclude = {"properties", "zabbixPassword", "mailPassword",
    "claudeApiKey", "accountMssqlPassword", "accequipmentMssqlPassword",
    // SNMPv2c community — фактично пароль на читання всього обладнання, ще й ходить мережею
    // відкритим текстом; без цього рядка гарантія «toString не вивалить секрети» була неповна
    "snmpCommunity", "snmpCommunityCelsius", "snmpCommunityRamos"})
@EqualsAndHashCode
@Getter
public class Config {

    @Getter(AccessLevel.NONE)
    private Properties properties;
    private Map<String, Map<String, String>> hosts;
    private Map<String, Map<String, String>> ramos;
    private boolean debug;
    private boolean incidentsEnabled;
    private boolean temperatureEnabled;
    private boolean ramosEnabled;
    private boolean zabbixEnabled;
    private boolean resilienceAuditEnabled;
    private String zabbixApi;
    private String zabbixUrl;
    private String zabbixUsername;
    private String zabbixPassword;
    private int zabbixGraphWidth;
    private int zabbixGraphHeight;
    @Getter(AccessLevel.NONE)
    @NonNull
    private String configPath;
    @NonNull
    private String dictionaryPdPath;
    @NonNull
    private String dictionarySdhPath;
    @NonNull
    private String dictionaryDeviceWordPath;
    private static final String HELP_PATH = "help.txt";

    @NonNull
    private String mailHostname;
    @NonNull
    private String mailUsername;
    @NonNull
    private String mailPassword;
    private boolean mailSsl;
    @NonNull
    private String zabbixFolder;

    @NonNull
    private String jnxOperatingDescr;
    @NonNull
    private String jnxOperatingTemp;
    @NonNull
    private String snmpCommunity;
    @NonNull
    private String snmpCommunityCelsius;
    @NonNull
    private String snmpCommunityRamos;
    @NonNull
    private String snmpHostsSuffix;

    @NonNull
    private String emailFrom;
    @NonNull
    private String emailReplyTo;
    @NonNull
    private List<String> emailTo;
    private String emailToDebug;
    @NonNull
    private String sendmailPath;

    private boolean claudeEnabled;
    @NonNull
    private String claudeApiKey;
    @NonNull
    private String claudeModel;
    private int claudeMaxTokens;
    private int claudeMinSentences;
    private int claudeMaxSentences;
    @NonNull
    private String historyResumeUrl;
    @Getter(AccessLevel.NONE)
    private Boolean claudeExplicit; // null = not explicitly set via property or CLI

    @NonNull
    private String snmpTrapFolder;
    private int snmpTrapDedupSeconds;
    private int snmpTrapColdstartLinkMinutes;
    @NonNull
    private String ramosTrapFolder;

    @NonNull
    private String accountMssqlUser;
    @NonNull
    private String accountMssqlPassword;
    @NonNull
    private String accountMssqlServer;
    @NonNull
    private String accountMssqlDatabase;
    @NonNull
    private String accequipmentMssqlUser;
    @NonNull
    private String accequipmentMssqlPassword;
    @NonNull
    private String accequipmentMssqlServer;
    @NonNull
    private String accequipmentMssqlDatabase;

    /**
     * Loads and validates the full configuration.
     *
     * @param args CLI arguments passed to {@code main()}
     * @throws IOException if the properties file or a dictionary file cannot be read
     */
    public Config(String[] args) throws IOException {
        initValues();
        parsePathArgs(args);
        loadProperties();
        generalProperties();
        parseFlagArgs(args);
        hostsProperties();
        ramosProperties();
        celsiusProperties();
        emailProperties();
        mssqlProperties();
        zabbixProperties();
        claudeProperties();
        historyResumeProperties();
        trapProperties();
    }

    /** Sets safe defaults for all fields before any properties or CLI arguments are applied. */
    private void initValues() {
        properties = new Properties();
        hosts = Collections.emptyMap();
        ramos = Collections.emptyMap();
        sendmailPath = "/usr/sbin/sendmail";
        configPath = null;
        dictionaryPdPath = null;
        dictionarySdhPath = null;
        dictionaryDeviceWordPath = null;
        claudeApiKey = "";
        claudeModel = "claude-haiku-4-5";
        claudeMaxTokens = 4096;
        claudeMinSentences = 5;
        claudeMaxSentences = 20;
        historyResumeUrl = "";
        claudeExplicit = null;
        snmpTrapFolder = "";
        snmpTrapDedupSeconds = 30;
        snmpTrapColdstartLinkMinutes = 5;
        ramosTrapFolder = "";
    }

    /**
     * Loads properties from the file pointed to by {@code --config=}, or from the bundled
     * {@code noczvit.properties} resource when no external path was given.
     *
     * @throws IOException if the file is missing or unreadable
     */
    private void loadProperties() throws IOException {
        // load(InputStream) decodes as ISO-8859-1 per spec, which mangles Cyrillic values
        // such as `snmp.ramos=...:name=Датацентр` (they go straight into the report HTML).
        if (configPath != null) {
            try (Reader input = new InputStreamReader(new FileInputStream(configPath), StandardCharsets.UTF_8)) {
                properties.load(input);
            } catch (IOException e) {
                throw new IOException("Failed to load configuration file: " + configPath, e);
            }
        } else {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("noczvit.properties")) {
                if (input == null) {
                    throw new IOException("Default noczvit.properties not found in resources");
                }
                properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Scans {@code args} for path-type arguments ({@code --config=}, {@code --dictionarypd=},
     * {@code --dictionarysdh=}, {@code --dictionarydeviceword=}) that must be known before
     * properties are loaded.
     */
    private void parsePathArgs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length()).trim();
            } else if (arg.startsWith("--dictionarypd=")) {
                dictionaryPdPath = arg.substring("--dictionarypd=".length()).trim();
            } else if (arg.startsWith("--dictionarysdh=")) {
                dictionarySdhPath = arg.substring("--dictionarysdh=".length()).trim();
            } else if (arg.startsWith("--dictionarydeviceword=")) {
                dictionaryDeviceWordPath = arg.substring("--dictionarydeviceword=".length()).trim();
            }
        }
    }

    /**
     * Processes boolean and feature-toggle CLI flags ({@code --incidents}, {@code --debug},
     * {@code --claude}, etc.). Prints help and exits on any unrecognised argument.
     */
    private void parseFlagArgs(String[] args) {
        for (String arg : args) {
            // --dictionarydeviceword= was missing here even though parsePathArgs() already
            // handles it — every real invocation with that flag hit the "Unknown argument"
            // branch below and killed the process via System.exit(1).
            if (arg.startsWith("--config=") || arg.startsWith("--dictionarypd=")
                    || arg.startsWith("--dictionarysdh=") || arg.startsWith("--dictionarydeviceword=")) {
                continue;
            }
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
                case "--zabbix" ->
                    zabbixEnabled = true;
                case "--no-zabbix" ->
                    zabbixEnabled = false;
                case "--resilience-audit" ->
                    resilienceAuditEnabled = true;
                case "--no-resilience-audit" ->
                    resilienceAuditEnabled = false;
                case "--debug" ->
                    debug = true;
                case "--no-debug" ->
                    debug = false;
                case "--claude" ->
                    claudeExplicit = true;
                case "--no-claude" ->
                    claudeExplicit = false;
                default -> {
                    printHelp();
                    log.error("Unknown argument: {}", arg);
                    System.exit(1);
                }
            }
        }
    }

    /** Prints the bundled {@code help.txt} resource to stderr. */
    private void printHelp() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(HELP_PATH)) {
            if (input == null) {
                log.warn("Help file not found in resources: {}", HELP_PATH);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read help file {}: {}", HELP_PATH, e.getMessage());
        }
    }

    /** Reads top-level boolean flags ({@code debug}, {@code incidents}, etc.) from properties. */
    private void generalProperties() {
        debug = Boolean.parseBoolean(properties.getProperty("debug", "false"));
        incidentsEnabled = Boolean.parseBoolean(properties.getProperty("incidents", "true"));
        temperatureEnabled = Boolean.parseBoolean(properties.getProperty("temperature", "true"));
        ramosEnabled = Boolean.parseBoolean(properties.getProperty("ramos", "false"));
        zabbixEnabled = Boolean.parseBoolean(properties.getProperty("zabbix", "false"));
        // Opt-in like ramos/zabbix, not on-by-default like incidents/temperature — new feature,
        // depends on Zabbix history/item.get behavior that needs verifying against a real
        // instance before it runs unattended in production.
        resilienceAuditEnabled = Boolean.parseBoolean(properties.getProperty("resilienceaudit", "false"));
        String claudeProp = properties.getProperty("claude");
        if (claudeProp != null) {
            claudeExplicit = Boolean.valueOf(claudeProp);
        }
    }

    /**
     * Parses {@code snmp.hosts} into an immutable {@code Map<hostname, Map<attr, value>>}.
     * Each entry has the form {@code hostname:key=val;key=val,...}.
     */
    private void hostsProperties() {
        hosts = parseKeyedAttributes("snmp.hosts");
    }

    /**
     * Parses {@code snmp.ramos} into an immutable {@code Map<ip, Map<attr, value>>}.
     * Each entry has the form {@code ip:key=val;key=val,...}.
     */
    private void ramosProperties() {
        ramos = parseKeyedAttributes("snmp.ramos");
    }

    /**
     * Parses a property holding comma-separated {@code key:attr=val;attr=val} entries into a
     * nested map. Entries without a {@code :} separator and attributes without {@code =} are
     * skipped. Both levels are wrapped unmodifiable — the maps are published to virtual threads
     * once parsing finishes and must never be mutated afterwards.
     *
     * @param propertyKey property name to read (e.g. {@code snmp.hosts})
     * @return immutable map of key → immutable attribute map; empty when the property is absent
     */
    private Map<String, Map<String, String>> parseKeyedAttributes(String propertyKey) {
        Map<String, Map<String, String>> result = new HashMap<>();
        String raw = properties.getProperty(propertyKey);
        if (raw != null) {
            for (String entry : raw.split(",")) {
                String[] parts = entry.split(":", 2);
                if (parts.length > 1) {
                    Map<String, String> attributes = new HashMap<>();
                    for (String attr : parts[1].split(";")) {
                        String[] kv = attr.split("=", 2);
                        if (kv.length > 1) {
                            attributes.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                    result.put(parts[0].trim(), Collections.unmodifiableMap(attributes));
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Reads SNMP community strings and OID settings for Celsius / temperature polling. */
    private void celsiusProperties() {
        jnxOperatingDescr = properties.getProperty("snmp.jnxOperatingDescr");
        jnxOperatingTemp = properties.getProperty("snmp.jnxOperatingTemp");
        snmpCommunity = properties.getProperty("snmp.community", "public");
        snmpCommunityCelsius = properties.getProperty("snmp.community.celsius", snmpCommunity);
        snmpCommunityRamos = properties.getProperty("snmp.community.ramos", snmpCommunity);
        snmpHostsSuffix = properties.getProperty("snmp.hosts.suffix", "");
    }

    /** Reads Zabbix API URL, credentials, and graph dimensions from properties. */
    private void zabbixProperties() {
        zabbixApi = properties.getProperty("zabbix.api", "");
        zabbixUrl = properties.getProperty("zabbix.url", "");
        zabbixUsername = properties.getProperty("zabbix.username", "");
        zabbixPassword = properties.getProperty("zabbix.password", "");
        zabbixGraphWidth = parseIntSafe(properties.getProperty("zabbix.graphwidth"), 640);
        zabbixGraphHeight = parseIntSafe(properties.getProperty("zabbix.graphheight"), 83);
    }

    /**
     * Parses an integer property value, returning {@code defaultValue} on null or format error.
     */
    private int parseIntSafe(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Reads MSSQL credentials for both the account DB and accequipment DB. */
    private void mssqlProperties() {
        accountMssqlUser = properties.getProperty("account-mssql-user", "");
        accountMssqlPassword = properties.getProperty("account-mssql-password", "");
        accountMssqlServer = properties.getProperty("account-mssql-server", "");
        accountMssqlDatabase = properties.getProperty("account-mssql-database", "");
        accequipmentMssqlUser = properties.getProperty("accequipment-mssql-user", "");
        accequipmentMssqlPassword = properties.getProperty("accequipment-mssql-password", "");
        accequipmentMssqlServer = properties.getProperty("accequipment-mssql-server", "");
        accequipmentMssqlDatabase = properties.getProperty("accequipment-mssql-database", "");
    }

    /** Reads IMAP and SMTP/sendmail settings together with email address lists. */
    private void emailProperties() {
        mailHostname = properties.getProperty("mail.hostname");
        mailUsername = properties.getProperty("mail.username");
        mailPassword = properties.getProperty("mail.password");
        mailSsl = Boolean.parseBoolean(properties.getProperty("mail.ssl", "false"));
        zabbixFolder = properties.getProperty("mail.zabbixFolder");

        emailFrom = properties.getProperty("email.from");
        emailReplyTo = properties.getProperty("email.replyTo");
        emailToDebug = properties.getProperty("email.toDebug");
        sendmailPath = properties.getProperty("email.sendmail", "/usr/sbin/sendmail");
        List<String> toList = new ArrayList<>();
        String toStr = properties.getProperty("email.to");
        if (toStr != null) {
            for (String email : toStr.split(",")) {
                toList.add(email.trim());
            }
        }
        emailTo = toList;
    }

    /**
     * Reads Claude AI settings and resolves the effective {@code claudeEnabled} flag.
     *
     * <p>Priority order: CLI flag ({@code --claude} / {@code --no-claude}) overrides the
     * {@code claude} property, which overrides the default (enabled iff not in debug mode).
     * Claude is auto-disabled when {@code claude.apikey} is blank.
     */
    private void claudeProperties() {
        String key = stripInlineComment(properties.getProperty("claude.apikey", ""));
        if (!key.isBlank()) {
            claudeApiKey = key;
        }
        String model = stripInlineComment(properties.getProperty("claude.model", ""));
        if (!model.isBlank()) {
            claudeModel = model;
        }
        String tokens = stripInlineComment(properties.getProperty("claude.tokens", ""));
        if (!tokens.isBlank()) {
            try {
                int t = Integer.parseInt(tokens);
                if (t > 0) {
                    claudeMaxTokens = t;
                }
            } catch (NumberFormatException e) {
                log.warn("claude.tokens: некоректне значення «{}» — використовується {}", tokens, claudeMaxTokens);
            }
        }
        String minSentences = stripInlineComment(properties.getProperty("claude.minsentences", ""));
        if (!minSentences.isBlank()) {
            try {
                int s = Integer.parseInt(minSentences);
                if (s > 0) {
                    claudeMinSentences = s;
                }
            } catch (NumberFormatException e) {
                log.warn("claude.minsentences: некоректне значення «{}» — використовується {}", minSentences, claudeMinSentences);
            }
        }
        String maxSentences = stripInlineComment(properties.getProperty("claude.maxsentences", ""));
        if (!maxSentences.isBlank()) {
            try {
                int s = Integer.parseInt(maxSentences);
                if (s > 0) {
                    claudeMaxSentences = s;
                }
            } catch (NumberFormatException e) {
                log.warn("claude.maxsentences: некоректне значення «{}» — використовується {}", maxSentences, claudeMaxSentences);
            }
        }
        // Default: enabled in normal mode, disabled in debug mode.
        // Explicit claude=.../--claude/--no-claude overrides the default.
        claudeEnabled = (claudeExplicit != null) ? claudeExplicit : !debug;
        if (claudeEnabled && claudeApiKey.isBlank()) {
            log.warn("Claude summary enabled but claude.apikey is not set — disabling");
            claudeEnabled = false;
        }
    }

    /**
     * Reads the optional {@code history.resume} JDBC URL for the SQLite cross-shift summary store.
     * Leaves {@link #historyResumeUrl} as an empty string when the property is absent or blank.
     */
    private void historyResumeProperties() {
        String url = stripInlineComment(properties.getProperty("history.resume", ""));
        if (!url.isBlank()) {
            historyResumeUrl = url;
        }
    }

    /**
     * Reads SNMP trap folder pattern and tuning parameters.
     * Leaves {@link #snmpTrapFolder} empty (feature disabled) when the property is absent or blank.
     */
    private void trapProperties() {
        String folder = stripInlineComment(properties.getProperty("snmp.trap.folder", ""));
        if (!folder.isBlank()) {
            snmpTrapFolder = folder;
        }
        try {
            String dedup = stripInlineComment(properties.getProperty("snmp.trap.dedup.seconds", ""));
            if (!dedup.isBlank()) {
                snmpTrapDedupSeconds = Integer.parseInt(dedup);
            }
        } catch (NumberFormatException e) {
            log.warn("snmp.trap.dedup.seconds: invalid value, using default {}", snmpTrapDedupSeconds);
        }
        try {
            String link = stripInlineComment(properties.getProperty("snmp.trap.coldstart.link.minutes", ""));
            if (!link.isBlank()) {
                snmpTrapColdstartLinkMinutes = Integer.parseInt(link);
            }
        } catch (NumberFormatException e) {
            log.warn("snmp.trap.coldstart.link.minutes: invalid value, using default {}", snmpTrapColdstartLinkMinutes);
        }
        String ramosFolder = stripInlineComment(properties.getProperty("ramos.trap.folder", ""));
        if (!ramosFolder.isBlank()) {
            ramosTrapFolder = ramosFolder;
        }
    }

    /**
     * Returns {@code true} when the SNMP trap folder is configured (feature is enabled).
     */
    public boolean isTrapEnabled() {
        return !snmpTrapFolder.isBlank();
    }

    /**
     * Returns {@code true} when the RAMOS trap email folder is configured (feature is enabled).
     */
    public boolean isRamosTrapEnabled() {
        return !ramosTrapFolder.isBlank();
    }

    /**
     * Strips a trailing inline comment ({@code # ...}) from a property value and trims whitespace.
     * Returns an empty string for null input.
     */
    private static String stripInlineComment(String value) {
        if (value == null) {
            return "";
        }
        int idx = value.indexOf('#');
        return idx >= 0 ? value.substring(0, idx).trim() : value.trim();
    }

    /**
     * Returns {@code true} when all four MSSQL connection properties (for both the account
     * and accequipment databases) are non-empty.
     */
    public boolean isDebtorsEnabled() {
        return !accountMssqlServer.isEmpty() && !accountMssqlDatabase.isEmpty()
                && !accequipmentMssqlServer.isEmpty() && !accequipmentMssqlDatabase.isEmpty();
    }

    /**
     * Returns {@code true} when the minimum required settings are present: email addresses
     * ({@code from}, {@code replyTo}, at least one {@code to}) and, if SNMP sections are
     * enabled, at least one community string.
     */
    public boolean isValid() {
        boolean isEmailValid = emailFrom != null && emailReplyTo != null && !emailTo.isEmpty();
        boolean isSnmpValid = true;
        if (isTemperatureEnabled() || isRamosEnabled()) {
            isSnmpValid = snmpCommunity != null || snmpCommunityCelsius != null || snmpCommunityRamos != null;
        }
        return isEmailValid && isSnmpValid;
    }
}
