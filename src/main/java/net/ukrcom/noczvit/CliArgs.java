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

import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

/**
 * Модель аргументів командного рядка (picocli). Кожне поле відповідає одній властивості
 * {@code noczvit.properties} — CLI-значення, коли задане, перевизначає властивість з файлу
 * (сам {@link Config} застосовує цей пріоритет, тут лише розбір).
 *
 * <p>Усі поля навмисно {@code Boolean}/{@code Integer}/{@code String} (не примітиви) і без
 * значень за замовчуванням: {@code null} означає «прапорець не задано в CLI», що дозволяє
 * {@link Config} відрізнити «явно передано» від «взагалі не передано» і застосувати властивість
 * з файлу лише в другому випадку. {@code --help}/{@code -h} і {@code --version}/{@code -V}
 * генеруються picocli автоматично ({@code mixinStandardHelpOptions}) — окремого {@code help.txt}
 * більше не потрібно, опис кожної опції нижче й {@code є} довідкою.
 */
@Command(name = "NOCZvit", sortOptions = false,
        versionProvider = CliArgs.VersionProvider.class,
        description = "Генератор автоматизованих NOC-звітів. Кожна властивість "
        + "noczvit.properties має відповідну опцію нижче — опція, якщо задана, "
        + "перевизначає властивість з файлу.")
public class CliArgs {

    // ---- Довідка/версія (замість picocli mixinStandardHelpOptions — щоб опис був українською) ----

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Показати цю довідку й завершити роботу.")
    boolean help;

    @Option(names = {"-V", "--version"}, versionHelp = true, description = "Показати версію й завершити роботу.")
    boolean version;

    // ---- Шляхи до файлів ----

    @Option(names = "--config", description = "Шлях до зовнішнього файлу конфігурації "
            + "(за замовчуванням — вбудований noczvit.properties)")
    String config;

    @Option(names = "--dictionarypd", description = "Шлях до зовнішнього словника PD")
    String dictionaryPd;

    @Option(names = "--dictionarysdh", description = "Шлях до зовнішнього словника SDH")
    String dictionarySdh;

    @Option(names = "--dictionarydeviceword", description = "Шлях до зовнішнього словника "
            + "типів пристроїв (за замовчуванням — вбудований dictionary_device_word.txt)")
    String dictionaryDeviceWord;

    // ---- Загальні перемикачі функцій (властивість без секції — верхній рівень) ----

    @Option(names = "--incidents", negatable = true, description = "Увімкнути/вимкнути блок інцидентів")
    Boolean incidents;

    @Option(names = "--temperature", negatable = true,
            description = "Увімкнути/вимкнути блок температури (SNMP Celsius)")
    Boolean temperature;

    @Option(names = "--ramos", negatable = true, description = "Увімкнути/вимкнути блок Ramos")
    Boolean ramos;

    @Option(names = "--zabbix", negatable = true,
            description = "Увімкнути/вимкнути вбудовування графіків температури з Zabbix")
    Boolean zabbix;

    @Option(names = "--debug", negatable = true,
            description = "Дебаг-режим: звіт надсилається на email.toDebug замість email.to")
    Boolean debug;

    @Option(names = "--resilience-audit", negatable = true, description = "Увімкнути/вимкнути секцію "
            + "«Аудит резервного живлення через непрямий сигнал» (потребує --zabbix)")
    Boolean resilienceAudit;

    @Option(names = "--resilienceaudit-ignoreinterfaceprefixes", description = "Префікси технічних "
            + "імен інтерфейсів (через кому), що виключаються з аудиту резервного живлення "
            + "(напр. wireguard,sstp)")
    String resilienceauditIgnoreinterfaceprefixes;

    // ---- SNMP: hosts / ramos / celsius ----

    @Option(names = "--snmp-hosts", description = "Мапа хостів SNMP-моніторингу температури "
            + "(hostname:attr=val;attr=val,...)")
    String snmpHosts;

    @Option(names = "--snmp-ramos", description = "Мапа хостів RAMOS (ip:attr=val;attr=val,...)")
    String snmpRamos;

    @Option(names = "--snmp-jnxoperatingdescr", description = "OID snmp.jnxOperatingDescr")
    String snmpJnxOperatingDescr;

    @Option(names = "--snmp-jnxoperatingtemp", description = "OID snmp.jnxOperatingTemp")
    String snmpJnxOperatingTemp;

    @Option(names = "--snmp-community", description = "SNMPv2c community за замовчуванням")
    String snmpCommunity;

    @Option(names = "--snmp-community-celsius", description = "SNMPv2c community для блоку температури")
    String snmpCommunityCelsius;

    @Option(names = "--snmp-community-ramos", description = "SNMPv2c community для блоку RAMOS")
    String snmpCommunityRamos;

    @Option(names = "--snmp-hosts-suffix", description = "Суфікс, що додається до hostname при SNMP-опитуванні")
    String snmpHostsSuffix;

    // ---- Zabbix ----

    @Option(names = "--zabbix-api", description = "URL Zabbix API (api_jsonrpc.php)")
    String zabbixApi;

    @Option(names = "--zabbix-url", description = "Базовий URL Zabbix web UI (для графіків)")
    String zabbixUrl;

    @Option(names = "--zabbix-username", description = "Ім'я користувача Zabbix API")
    String zabbixUsername;

    @Option(names = "--zabbix-password", description = "Пароль Zabbix API")
    String zabbixPassword;

    @Option(names = "--zabbix-graphwidth", description = "Ширина вбудованих графіків Zabbix (px)")
    Integer zabbixGraphwidth;

    @Option(names = "--zabbix-graphheight", description = "Висота вбудованих графіків Zabbix (px)")
    Integer zabbixGraphheight;

    // ---- MSSQL: боржники (account) та обладнання (accequipment) ----

    @Option(names = "--account-mssql-user", description = "Користувач MSSQL, БД боржників (account)")
    String accountMssqlUser;

    @Option(names = "--account-mssql-password", description = "Пароль MSSQL, БД боржників (account)")
    String accountMssqlPassword;

    @Option(names = "--account-mssql-server", description = "Сервер MSSQL, БД боржників (account)")
    String accountMssqlServer;

    @Option(names = "--account-mssql-database", description = "Назва БД MSSQL, боржники (account)")
    String accountMssqlDatabase;

    @Option(names = "--accequipment-mssql-user", description = "Користувач MSSQL, БД обладнання (accequipment)")
    String accequipmentMssqlUser;

    @Option(names = "--accequipment-mssql-password", description = "Пароль MSSQL, БД обладнання (accequipment)")
    String accequipmentMssqlPassword;

    @Option(names = "--accequipment-mssql-server", description = "Сервер MSSQL, БД обладнання (accequipment)")
    String accequipmentMssqlServer;

    @Option(names = "--accequipment-mssql-database", description = "Назва БД MSSQL, обладнання (accequipment)")
    String accequipmentMssqlDatabase;

    // ---- IMAP/SMTP/email ----

    @Option(names = "--mail-hostname", description = "Hostname IMAP-сервера")
    String mailHostname;

    @Option(names = "--mail-username", description = "Ім'я користувача IMAP")
    String mailUsername;

    @Option(names = "--mail-password", description = "Пароль IMAP")
    String mailPassword;

    @Option(names = "--mail-ssl", negatable = true, description = "Використовувати SSL/IMAPS для з'єднання")
    Boolean mailSsl;

    @Option(names = "--mail-zabbixfolder", description = "IMAP-тека з листами Zabbix")
    String mailZabbixfolder;

    @Option(names = "--email-from", description = "Адреса відправника (From)")
    String emailFrom;

    @Option(names = "--email-replyto", description = "Адреса для відповіді (Reply-To)")
    String emailReplyto;

    @Option(names = "--email-to", description = "Адреси одержувачів звіту (через кому)")
    String emailTo;

    @Option(names = "--email-todebug", description = "Адреса одержувача в режимі --debug")
    String emailTodebug;

    @Option(names = "--email-sendmail", description = "Шлях до бінарника sendmail")
    String emailSendmail;

    // ---- Claude AI ----

    @Option(names = "--claude", negatable = true, description = "Увімкнути/вимкнути AI-резюме зміни "
            + "(за замовчуванням: увімк. в нормальному режимі, вимк. в --debug)")
    Boolean claude;

    @Option(names = "--claude-apikey", description = "API-ключ Claude (console.anthropic.com)")
    String claudeApikey;

    @Option(names = "--claude-model", description = "Модель Claude для резюме")
    String claudeModel;

    @Option(names = "--claude-tokens", description = "Максимум токенів відповіді Claude")
    Integer claudeTokens;

    @Option(names = "--claude-minsentences", description = "Мінімум речень у резюме Claude")
    Integer claudeMinsentences;

    @Option(names = "--claude-maxsentences", description = "Максимум речень у резюме Claude")
    Integer claudeMaxsentences;

    // ---- Історія (міжзмінна пам'ять Claude) ----

    @Option(names = "--history-resume", description = "JDBC URL SQLite для зведень між змінами")
    String historyResume;

    // ---- SNMP trap (Emerson) / RAMOS trap ----

    @Option(names = "--snmp-trap-folder", description = "IMAP-тека з SNMP-трапами Emerson")
    String snmpTrapFolder;

    @Option(names = "--snmp-trap-dedup-seconds", description = "Вікно дедуплікації трапів, секунд")
    Integer snmpTrapDedupSeconds;

    @Option(names = "--snmp-trap-coldstart-link-minutes",
            description = "Вікно прив'язки Cold Start до відновлення хоста, хвилин")
    Integer snmpTrapColdstartLinkMinutes;

    @Option(names = "--ramos-trap-folder", description = "IMAP-тека з трапами RAMOS")
    String ramosTrapFolder;

    /** Читає версію з {@code version.properties} для {@code --version} — той самий підхід, що й {@code EmailSender}. */
    static class VersionProvider implements IVersionProvider {

        @Override
        public String[] getVersion() throws Exception {
            var props = new java.util.Properties();
            try (var input = CliArgs.class.getClassLoader().getResourceAsStream("version.properties")) {
                if (input != null) {
                    props.load(input);
                }
            }
            return new String[]{"NOCZvit " + props.getProperty("project.version", "unknown")};
        }
    }
}
