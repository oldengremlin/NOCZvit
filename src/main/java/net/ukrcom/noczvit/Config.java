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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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
import picocli.CommandLine;

/**
 * Конфігурація застосунку, що завантажується з файлу властивостей і перевизначається
 * аргументами командного рядка (picocli, {@link CliArgs}) — кожній властивості відповідає
 * опція, і опція, коли задана, перевизначає властивість з файлу.
 *
 * <p>Послідовність побудови (усі виклики йдуть з конструктора по черзі):
 * {@link #initValues()} → {@link #parseCliArgs(String[])} → {@link #loadProperties()} →
 * {@link #generalProperties(CliArgs)} → {@link #hostsProperties(CliArgs)} →
 * {@link #ramosProperties(CliArgs)} → {@link #celsiusProperties(CliArgs)} →
 * {@link #emailProperties(CliArgs)} → {@link #mssqlProperties(CliArgs)} →
 * {@link #zabbixProperties(CliArgs)} → {@link #claudeProperties(CliArgs)} →
 * {@link #historyResumeProperties(CliArgs)} → {@link #trapProperties(CliArgs)}.
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
     * <p>{@code --help}/{@code -h} і {@code --version}/{@code -V} обробляються тут-таки й
     * завершують процес (picocli {@code mixinStandardHelpOptions}) — до бізнес-логіки не
     * доходить. Так само, якщо не задано ні {@code --config=}/вбудованого
     * {@code noczvit.properties}, ні жодного аргументу CLI — довідка виводиться замість
     * подальшого падіння на порожній конфігурації.
     *
     * @param args аргументи командного рядка, передані до {@code main()}
     * @throws IOException якщо файл властивостей або файл словника неможливо прочитати
     */
    public Config(String[] args) throws IOException {
        initValues();
        CliArgs cli = parseCliArgs(args);
        configPath = cli.config;
        dictionaryPdPath = cli.dictionaryPd;
        dictionarySdhPath = cli.dictionarySdh;
        dictionaryDeviceWordPath = cli.dictionaryDeviceWord;

        if (hasNoConfiguration(args, propertiesFileExists(configPath))) {
            log.error("No configuration file and no CLI arguments provided");
            new CommandLine(new CliArgs()).usage(utf8(System.err));
            System.exit(1);
        }

        loadProperties();
        generalProperties(cli);
        hostsProperties(cli);
        ramosProperties(cli);
        celsiusProperties(cli);
        emailProperties(cli);
        mssqlProperties(cli);
        zabbixProperties(cli);
        claudeProperties(cli);
        historyResumeProperties(cli);
        trapProperties(cli);
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
     * Розбирає {@code args} через picocli. {@code --help}/{@code -h} і {@code --version}/
     * {@code -V} обробляються тут-таки (виводяться й процес завершується) — жоден виклик
     * далі по конструктору їх не побачить. Невідомий аргумент чи хибне значення опції теж
     * завершує процес (з кодом 1) після виводу довідки — так само, як і раніше з ручним
     * switch, лише повідомлення тепер генерує сам picocli.
     */
    private CliArgs parseCliArgs(String[] args) {
        CliArgs cli = new CliArgs();
        CommandLine cmd = new CommandLine(cli);
        try {
            cmd.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            log.error("Invalid CLI arguments: {}", e.getMessage());
            cmd.usage(utf8(System.err));
            System.exit(1);
        }
        if (cmd.isUsageHelpRequested()) {
            cmd.usage(utf8(System.out));
            System.exit(0);
        }
        if (cmd.isVersionHelpRequested()) {
            cmd.printVersionHelp(utf8(System.out));
            System.exit(0);
        }
        return cli;
    }

    /**
     * Обгортає потік у {@link PrintWriter} з примусовим UTF-8 — довідка й помилки CLI
     * містять кирилицю, а {@code System.out}/{@code System.err} без явно заданого
     * {@code LANG} (типово в cron) кодують за застарілим ASCII/{@code file.encoding},
     * перетворюючи кожну кириличну літеру на {@code ?}.
     */
    private static PrintWriter utf8(OutputStream out) {
        return new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
    }

    /**
     * {@code true}, коли немає ні файлу конфігурації (ні зовнішнього через {@code --config=},
     * ні вбудованого {@code noczvit.properties}), ні жодного аргументу командного рядка —
     * повністю порожній виклик, для якого змістовніше показати довідку, ніж провалитись
     * далі на відсутніх обов'язкових властивостях. Package-private і без побічних ефектів
     * (I/O винесено в {@link #propertiesFileExists}) — щоб можна було перевірити напряму,
     * не викликаючи {@code System.exit()} в тестовому процесі.
     */
    static boolean hasNoConfiguration(String[] args, boolean propertiesFileExists) {
        return args.length == 0 && !propertiesFileExists;
    }

    /**
     * {@code true}, коли файл властивостей насправді доступний: зовнішній шлях
     * ({@code configPath}) існує як файл, або (коли шлях не задано) вбудований ресурс
     * {@code noczvit.properties} присутній у classpath.
     */
    private static boolean propertiesFileExists(String configPath) {
        if (configPath != null) {
            return new File(configPath).isFile();
        }
        return Config.class.getClassLoader().getResource("noczvit.properties") != null;
    }

    /** CLI-значення, якщо задане, інакше — сирий рядок властивості (може бути {@code null}). */
    private String pick(String cliValue, String propertyKey) {
        return cliValue != null ? cliValue : properties.getProperty(propertyKey);
    }

    /** Те саме, що {@link #pick(String, String)}, з фолбеком на {@code defaultValue}. */
    private String pick(String cliValue, String propertyKey, String defaultValue) {
        return cliValue != null ? cliValue : properties.getProperty(propertyKey, defaultValue);
    }

    /** CLI-прапорець, якщо заданий, інакше — булева властивість (чи {@code defaultValue}). */
    private boolean pickBool(Boolean cliValue, String propertyKey, boolean defaultValue) {
        return cliValue != null ? cliValue
                : Boolean.parseBoolean(properties.getProperty(propertyKey, String.valueOf(defaultValue)));
    }

    /** CLI-значення, якщо задане, інакше — ціла властивість через {@link #parseIntSafe}. */
    private int pickInt(Integer cliValue, String propertyKey, int defaultValue) {
        return cliValue != null ? cliValue : parseIntSafe(properties.getProperty(propertyKey), defaultValue);
    }

    /** Читає верхньорівневі булеві прапорці ({@code debug}, {@code incidents} тощо) з CLI/властивостей. */
    private void generalProperties(CliArgs cli) {
        debug = pickBool(cli.debug, "debug", false);
        incidentsEnabled = pickBool(cli.incidents, "incidents", true);
        temperatureEnabled = pickBool(cli.temperature, "temperature", true);
        ramosEnabled = pickBool(cli.ramos, "ramos", false);
        zabbixEnabled = pickBool(cli.zabbix, "zabbix", false);
        // Opt-in, як ramos/zabbix, а не увімкнено за замовчуванням, як incidents/temperature —
        // нова функція, що залежить від поведінки Zabbix history/item.get, яку потрібно
        // перевірити на реальному інстансі, перш ніж запускати без нагляду в продакшені.
        resilienceAuditEnabled = pickBool(cli.resilienceAudit, "resilienceaudit", false);
        // Zabbix бачить лише текстове ім'я інтерфейсу (ifDescr/ifName), не ifType — типи на
        // кшталт MikroTik wireguard/sstp/l2tp/pptp нічим не позначені в SNMP-даних, які тут
        // доступні, а самі імена користувач може перейменувати як завгодно. Тому єдиний
        // робочий варіант без додаткових SNMP-запитів — порівняння префікса імені зі списком,
        // який задає сам адміністратор (порожньо за замовчуванням — нічого не виключається).
        resilienceIgnoredInterfacePrefixes = parseCommaList(pick(
                cli.resilienceauditIgnoreinterfaceprefixes, "resilienceaudit.ignoreinterfaceprefixes", ""));
        Boolean claudeFromProperty = null;
        String claudeProp = properties.getProperty("claude");
        if (claudeProp != null) {
            claudeFromProperty = Boolean.valueOf(claudeProp);
        }
        claudeExplicit = cli.claude != null ? cli.claude : claudeFromProperty;
    }

    /**
     * Розбирає {@code snmp.hosts} (CLI {@code --snmp-hosts} перевизначає властивість) у незмінну
     * {@code Map<hostname, Map<attr, value>>}. Кожен запис має вигляд {@code hostname:key=val;key=val,...}.
     */
    private void hostsProperties(CliArgs cli) {
        hosts = parseKeyedAttributes(pick(cli.snmpHosts, "snmp.hosts"));
    }

    /**
     * Розбирає {@code snmp.ramos} (CLI {@code --snmp-ramos} перевизначає властивість) у незмінну
     * {@code Map<ip, Map<attr, value>>}. Кожен запис має вигляд {@code ip:key=val;key=val,...}.
     */
    private void ramosProperties(CliArgs cli) {
        ramos = parseKeyedAttributes(pick(cli.snmpRamos, "snmp.ramos"));
    }

    /**
     * Розбирає рядок виду {@code key:attr=val;attr=val,key2:...} у вкладену мапу. Записи без
     * роздільника {@code :} та атрибути без {@code =} пропускаються. Обидва рівні обгортаються
     * як незмінні — ці мапи публікуються віртуальним потокам після завершення розбору й ніколи
     * не повинні змінюватись після цього.
     *
     * @param raw сирий рядок (з CLI або властивості); {@code null} дає порожню мапу
     * @return незмінна мапа ключ → незмінна мапа атрибутів; порожня, якщо {@code raw} відсутній
     */
    private Map<String, Map<String, String>> parseKeyedAttributes(String raw) {
        Map<String, Map<String, String>> result = new HashMap<>();
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

    /** Читає SNMP community-рядки та налаштування OID для опитування Celsius / температури (CLI/властивості). */
    private void celsiusProperties(CliArgs cli) {
        jnxOperatingDescr = pick(cli.snmpJnxOperatingDescr, "snmp.jnxOperatingDescr");
        jnxOperatingTemp = pick(cli.snmpJnxOperatingTemp, "snmp.jnxOperatingTemp");
        snmpCommunity = pick(cli.snmpCommunity, "snmp.community", "public");
        snmpCommunityCelsius = pick(cli.snmpCommunityCelsius, "snmp.community.celsius", snmpCommunity);
        snmpCommunityRamos = pick(cli.snmpCommunityRamos, "snmp.community.ramos", snmpCommunity);
        snmpHostsSuffix = pick(cli.snmpHostsSuffix, "snmp.hosts.suffix", "");
    }

    /** Читає URL Zabbix API, облікові дані та розміри графіків з CLI/властивостей. */
    private void zabbixProperties(CliArgs cli) {
        zabbixApi = pick(cli.zabbixApi, "zabbix.api", "");
        zabbixUrl = pick(cli.zabbixUrl, "zabbix.url", "");
        zabbixUsername = pick(cli.zabbixUsername, "zabbix.username", "");
        zabbixPassword = pick(cli.zabbixPassword, "zabbix.password", "");
        zabbixGraphWidth = pickInt(cli.zabbixGraphwidth, "zabbix.graphwidth", 640);
        zabbixGraphHeight = pickInt(cli.zabbixGraphheight, "zabbix.graphheight", 83);
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

    /** Читає облікові дані MSSQL як для БД account, так і для БД accequipment (CLI/властивості). */
    private void mssqlProperties(CliArgs cli) {
        accountMssqlUser = pick(cli.accountMssqlUser, "account-mssql-user", "");
        accountMssqlPassword = pick(cli.accountMssqlPassword, "account-mssql-password", "");
        accountMssqlServer = pick(cli.accountMssqlServer, "account-mssql-server", "");
        accountMssqlDatabase = pick(cli.accountMssqlDatabase, "account-mssql-database", "");
        accequipmentMssqlUser = pick(cli.accequipmentMssqlUser, "accequipment-mssql-user", "");
        accequipmentMssqlPassword = pick(cli.accequipmentMssqlPassword, "accequipment-mssql-password", "");
        accequipmentMssqlServer = pick(cli.accequipmentMssqlServer, "accequipment-mssql-server", "");
        accequipmentMssqlDatabase = pick(cli.accequipmentMssqlDatabase, "accequipment-mssql-database", "");
    }

    /** Читає налаштування IMAP та SMTP/sendmail разом зі списками email-адрес (CLI/властивості). */
    private void emailProperties(CliArgs cli) {
        mailHostname = pick(cli.mailHostname, "mail.hostname");
        mailUsername = pick(cli.mailUsername, "mail.username");
        mailPassword = pick(cli.mailPassword, "mail.password");
        mailSsl = pickBool(cli.mailSsl, "mail.ssl", false);
        zabbixFolder = pick(cli.mailZabbixfolder, "mail.zabbixFolder");

        emailFrom = pick(cli.emailFrom, "email.from");
        emailReplyTo = pick(cli.emailReplyto, "email.replyTo");
        emailToDebug = pick(cli.emailTodebug, "email.toDebug");
        sendmailPath = pick(cli.emailSendmail, "email.sendmail", "/usr/sbin/sendmail");
        List<String> toList = new ArrayList<>();
        String toStr = pick(cli.emailTo, "email.to");
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
     * Числові й рядкові властивості так само мають CLI-відповідники ({@code --claude-tokens} тощо),
     * що перевизначають властивість.
     */
    private void claudeProperties(CliArgs cli) {
        String key = cli.claudeApikey != null ? cli.claudeApikey
                : stripInlineComment(properties.getProperty("claude.apikey", ""));
        if (!key.isBlank()) {
            claudeApiKey = key;
        }
        String model = cli.claudeModel != null ? cli.claudeModel
                : stripInlineComment(properties.getProperty("claude.model", ""));
        if (!model.isBlank()) {
            claudeModel = model;
        }
        String tokens = cli.claudeTokens != null ? String.valueOf(cli.claudeTokens)
                : stripInlineComment(properties.getProperty("claude.tokens", ""));
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
        String minSentences = cli.claudeMinsentences != null ? String.valueOf(cli.claudeMinsentences)
                : stripInlineComment(properties.getProperty("claude.minsentences", ""));
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
        String maxSentences = cli.claudeMaxsentences != null ? String.valueOf(cli.claudeMaxsentences)
                : stripInlineComment(properties.getProperty("claude.maxsentences", ""));
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
     * Читає опціональний JDBC URL {@code history.resume} (CLI {@code --history-resume}
     * перевизначає властивість) для сховища SQLite зведень між змінами. Залишає
     * {@link #historyResumeUrl} порожнім рядком, якщо ні CLI, ні властивість не задані.
     */
    private void historyResumeProperties(CliArgs cli) {
        String url = stripInlineComment(pick(cli.historyResume, "history.resume", ""));
        if (!url.isBlank()) {
            historyResumeUrl = url;
        }
    }

    /**
     * Читає шаблон папки SNMP trap та параметри налаштування (CLI перевизначає властивість).
     * Залишає {@link #snmpTrapFolder} порожнім (функція вимкнена), якщо ні CLI, ні властивість
     * не задані.
     */
    private void trapProperties(CliArgs cli) {
        String folder = stripInlineComment(pick(cli.snmpTrapFolder, "snmp.trap.folder", ""));
        if (!folder.isBlank()) {
            snmpTrapFolder = folder;
        }
        try {
            String dedup = cli.snmpTrapDedupSeconds != null ? String.valueOf(cli.snmpTrapDedupSeconds)
                    : stripInlineComment(properties.getProperty("snmp.trap.dedup.seconds", ""));
            if (!dedup.isBlank()) {
                snmpTrapDedupSeconds = Integer.parseInt(dedup);
            }
        } catch (NumberFormatException e) {
            log.warn("snmp.trap.dedup.seconds: invalid value, using default {}", snmpTrapDedupSeconds);
        }
        try {
            String link = cli.snmpTrapColdstartLinkMinutes != null ? String.valueOf(cli.snmpTrapColdstartLinkMinutes)
                    : stripInlineComment(properties.getProperty("snmp.trap.coldstart.link.minutes", ""));
            if (!link.isBlank()) {
                snmpTrapColdstartLinkMinutes = Integer.parseInt(link);
            }
        } catch (NumberFormatException e) {
            log.warn("snmp.trap.coldstart.link.minutes: invalid value, using default {}", snmpTrapColdstartLinkMinutes);
        }
        String ramosFolder = stripInlineComment(pick(cli.ramosTrapFolder, "ramos.trap.folder", ""));
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
