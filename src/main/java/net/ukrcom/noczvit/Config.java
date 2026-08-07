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
import java.util.Arrays;
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
 * Конфігурація застосунку, що завантажується з файлу властивостей і перевизначається
 * аргументами командного рядка.
 *
 * <p>Послідовність побудови (усі виклики йдуть з конструктора по черзі):
 * {@link #initValues()} → {@link #parsePathArgs(String[])} → {@link #loadProperties()} →
 * {@link #generalProperties()} → {@link #parseFlagArgs(String[])} →
 * {@link #hostsProperties()} → {@link #ramosProperties()} → {@link #celsiusProperties()} →
 * {@link #emailProperties()} → {@link #mssqlProperties()} → {@link #zabbixProperties()} →
 * {@link #claudeProperties()} → {@link #historyResumeProperties()} → {@link #trapProperties()}.
 */
@Slf4j
// секрети виключені, щоб майбутній log.debug("config={}", config) не міг їх злити;
// `properties` містить усі пари ключ-значення, тобто всі секрети ще раз
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
    @NonNull
    private List<String> resilienceIgnoredInterfacePrefixes;
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
    private Boolean claudeExplicit; // null = не задано явно ні властивістю, ні CLI

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
     * Завантажує й перевіряє повну конфігурацію.
     *
     * @param args аргументи командного рядка, передані до {@code main()}
     * @throws IOException якщо файл властивостей або файл словника неможливо прочитати
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

    /** Встановлює безпечні значення за замовчуванням для всіх полів до застосування властивостей чи аргументів CLI. */
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
        resilienceIgnoredInterfacePrefixes = Collections.emptyList();
    }

    /**
     * Завантажує властивості з файлу, на який вказує {@code --config=}, або з вбудованого
     * ресурсу {@code noczvit.properties}, якщо зовнішній шлях не задано.
     *
     * @throws IOException якщо файл відсутній або нечитабельний
     */
    private void loadProperties() throws IOException {
        // load(InputStream) декодує як ISO-8859-1 за специфікацією, що псує кириличні значення
        // на кшталт `snmp.ramos=...:name=Датацентр` (вони йдуть напряму в HTML звіту).
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
     * Сканує {@code args} на предмет аргументів-шляхів ({@code --config=}, {@code --dictionarypd=},
     * {@code --dictionarysdh=}, {@code --dictionarydeviceword=}), які мають бути відомі ще до
     * завантаження властивостей.
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
     * Обробляє булеві прапорці та перемикачі функціональності CLI ({@code --incidents},
     * {@code --debug}, {@code --claude} тощо). При будь-якому нерозпізнаному аргументі
     * виводить довідку й завершує процес.
     */
    private void parseFlagArgs(String[] args) {
        for (String arg : args) {
            // --dictionarydeviceword= тут бракувало, хоча parsePathArgs() уже його обробляє —
            // кожен реальний виклик із цим прапорцем потрапляв у гілку "Unknown argument"
            // нижче й убивав процес через System.exit(1).
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

    /** Виводить вбудований ресурс {@code help.txt} у stderr. */
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

    /** Читає верхньорівневі булеві прапорці ({@code debug}, {@code incidents} тощо) з властивостей. */
    private void generalProperties() {
        debug = Boolean.parseBoolean(properties.getProperty("debug", "false"));
        incidentsEnabled = Boolean.parseBoolean(properties.getProperty("incidents", "true"));
        temperatureEnabled = Boolean.parseBoolean(properties.getProperty("temperature", "true"));
        ramosEnabled = Boolean.parseBoolean(properties.getProperty("ramos", "false"));
        zabbixEnabled = Boolean.parseBoolean(properties.getProperty("zabbix", "false"));
        // Opt-in, як ramos/zabbix, а не увімкнено за замовчуванням, як incidents/temperature —
        // нова функція, що залежить від поведінки Zabbix history/item.get, яку потрібно
        // перевірити на реальному інстансі, перш ніж запускати без нагляду в продакшені.
        resilienceAuditEnabled = Boolean.parseBoolean(properties.getProperty("resilienceaudit", "false"));
        // Zabbix бачить лише текстове ім'я інтерфейсу (ifDescr/ifName), не ifType — типи на
        // кшталт MikroTik wireguard/sstp/l2tp/pptp нічим не позначені в SNMP-даних, які тут
        // доступні, а самі імена користувач може перейменувати як завгодно. Тому єдиний
        // робочий варіант без додаткових SNMP-запитів — порівняння префікса імені зі списком,
        // який задає сам адміністратор (порожньо за замовчуванням — нічого не виключається).
        resilienceIgnoredInterfacePrefixes = parseCommaList(
                properties.getProperty("resilienceaudit.ignoreinterfaceprefixes", ""));
        String claudeProp = properties.getProperty("claude");
        if (claudeProp != null) {
            claudeExplicit = Boolean.valueOf(claudeProp);
        }
    }

    /**
     * Розбирає {@code snmp.hosts} у незмінну {@code Map<hostname, Map<attr, value>>}.
     * Кожен запис має вигляд {@code hostname:key=val;key=val,...}.
     */
    private void hostsProperties() {
        hosts = parseKeyedAttributes("snmp.hosts");
    }

    /**
     * Розбирає {@code snmp.ramos} у незмінну {@code Map<ip, Map<attr, value>>}.
     * Кожен запис має вигляд {@code ip:key=val;key=val,...}.
     */
    private void ramosProperties() {
        ramos = parseKeyedAttributes("snmp.ramos");
    }

    /**
     * Розбирає властивість, що містить розділені комою записи {@code key:attr=val;attr=val},
     * у вкладену мапу. Записи без роздільника {@code :} та атрибути без {@code =}
     * пропускаються. Обидва рівні обгортаються як незмінні — ці мапи публікуються віртуальним
     * потокам після завершення розбору й ніколи не повинні змінюватись після цього.
     *
     * @param propertyKey ім'я властивості для читання (напр. {@code snmp.hosts})
     * @return незмінна мапа ключ → незмінна мапа атрибутів; порожня, якщо властивість відсутня
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

    /** Читає SNMP community-рядки та налаштування OID для опитування Celsius / температури. */
    private void celsiusProperties() {
        jnxOperatingDescr = properties.getProperty("snmp.jnxOperatingDescr");
        jnxOperatingTemp = properties.getProperty("snmp.jnxOperatingTemp");
        snmpCommunity = properties.getProperty("snmp.community", "public");
        snmpCommunityCelsius = properties.getProperty("snmp.community.celsius", snmpCommunity);
        snmpCommunityRamos = properties.getProperty("snmp.community.ramos", snmpCommunity);
        snmpHostsSuffix = properties.getProperty("snmp.hosts.suffix", "");
    }

    /** Читає URL Zabbix API, облікові дані та розміри графіків з властивостей. */
    private void zabbixProperties() {
        zabbixApi = properties.getProperty("zabbix.api", "");
        zabbixUrl = properties.getProperty("zabbix.url", "");
        zabbixUsername = properties.getProperty("zabbix.username", "");
        zabbixPassword = properties.getProperty("zabbix.password", "");
        zabbixGraphWidth = parseIntSafe(properties.getProperty("zabbix.graphwidth"), 640);
        zabbixGraphHeight = parseIntSafe(properties.getProperty("zabbix.graphheight"), 83);
    }

    /**
     * Розбирає цілочисельне значення властивості, повертаючи {@code defaultValue} при null
     * чи помилці формату.
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

    /**
     * Розбирає властивість виду {@code "a, B,, c"} на список непорожніх, обрізаних від пробілів,
     * приведених до нижнього регістру записів ({@code ["a", "b", "c"]}). Порожні елементи (в
     * т.ч. від подвійної коми) пропускаються. Порожній чи відсутній рядок дає порожній список.
     */
    private static List<String> parseCommaList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
    }

    /** Читає облікові дані MSSQL як для БД account, так і для БД accequipment. */
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

    /** Читає налаштування IMAP та SMTP/sendmail разом зі списками email-адрес. */
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
     * Читає налаштування Claude AI й обчислює фактичний прапорець {@code claudeEnabled}.
     *
     * <p>Порядок пріоритету: прапорець CLI ({@code --claude} / {@code --no-claude}) перевизначає
     * властивість {@code claude}, яка перевизначає значення за замовчуванням (увімкнено, лише
     * якщо не в режимі debug). Claude автоматично вимикається, якщо {@code claude.apikey} порожній.
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
        // За замовчуванням: увімкнено в звичайному режимі, вимкнено в режимі debug.
        // Явне claude=.../--claude/--no-claude перевизначає значення за замовчуванням.
        claudeEnabled = (claudeExplicit != null) ? claudeExplicit : !debug;
        if (claudeEnabled && claudeApiKey.isBlank()) {
            log.warn("Claude summary enabled but claude.apikey is not set — disabling");
            claudeEnabled = false;
        }
    }

    /**
     * Читає опціональний JDBC URL {@code history.resume} для сховища SQLite зведень між змінами.
     * Залишає {@link #historyResumeUrl} порожнім рядком, якщо властивість відсутня чи порожня.
     */
    private void historyResumeProperties() {
        String url = stripInlineComment(properties.getProperty("history.resume", ""));
        if (!url.isBlank()) {
            historyResumeUrl = url;
        }
    }

    /**
     * Читає шаблон папки SNMP trap та параметри налаштування.
     * Залишає {@link #snmpTrapFolder} порожнім (функція вимкнена), якщо властивість відсутня чи порожня.
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
     * Повертає {@code true}, коли папку SNMP trap налаштовано (функція увімкнена).
     *
     * @return {@code true}, якщо {@code snmpTrapFolder} задано
     */
    public boolean isTrapEnabled() {
        return !snmpTrapFolder.isBlank();
    }

    /**
     * Повертає {@code true}, коли папку email для RAMOS trap налаштовано (функція увімкнена).
     *
     * @return {@code true}, якщо {@code ramosTrapFolder} задано
     */
    public boolean isRamosTrapEnabled() {
        return !ramosTrapFolder.isBlank();
    }

    /**
     * Відсікає кінцевий вбудований коментар ({@code # ...}) зі значення властивості й обрізає пробіли.
     * Повертає порожній рядок для {@code null} на вході.
     */
    private static String stripInlineComment(String value) {
        if (value == null) {
            return "";
        }
        int idx = value.indexOf('#');
        return idx >= 0 ? value.substring(0, idx).trim() : value.trim();
    }

    /**
     * Повертає {@code true}, коли всі чотири властивості з'єднання MSSQL (для обох баз даних —
     * account і accequipment) непорожні.
     *
     * @return {@code true}, якщо секцію боржників можна вмикати
     */
    public boolean isDebtorsEnabled() {
        return !accountMssqlServer.isEmpty() && !accountMssqlDatabase.isEmpty()
                && !accequipmentMssqlServer.isEmpty() && !accequipmentMssqlDatabase.isEmpty();
    }

    /**
     * Повертає {@code true}, коли присутній мінімально необхідний набір налаштувань: email-адреси
     * ({@code from}, {@code replyTo}, щонайменше одна {@code to}) і, якщо секції SNMP увімкнені,
     * щонайменше один community-рядок.
     *
     * @return {@code true}, якщо конфігурація придатна для запуску
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
