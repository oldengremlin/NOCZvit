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

import net.ukrcom.noczvit.imap.Client;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

/**
 * NOC report on incidents registered automatically by Zabbix and OSM systems
 *
 * @author olden
 */
@Slf4j
public class NOCZvit {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws MessagingException, IOException {
        // Set log level to DEBUG before Config is instantiated so all initialization is visible
        if (Arrays.asList(args).contains("--debug")) {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.DEBUG);
        }

        try {
            Config config = new Config(args);
            if (!config.isValid()) {
                log.error("Invalid configuration. Ensure all required email properties are set.");
                System.exit(1);
            }

            // Check if all sections are disabled
            if (!config.isIncidentsEnabled() && !config.isTemperatureEnabled() && !config.isRamosEnabled() && !config.isDebtorsEnabled()) {
                log.info("All report sections are disabled, skipping email sending.");
                return;
            }

            boolean isInteractive = System.console() != null;
            LocalDate currentDate = LocalDate.now();
            LocalDate yesterday = currentDate.minusDays(1);

            LocalDateTime prevDutyBegin = LocalDateTime.parse(yesterday + " 20:00:00", DATE_TIME_FORMATTER);
            LocalDateTime prevDutyEnd = LocalDateTime.parse(currentDate + " 07:59:59", DATE_TIME_FORMATTER);
            LocalDateTime currDutyBegin = LocalDateTime.parse(currentDate + " 08:00:00", DATE_TIME_FORMATTER);
            LocalDateTime currDutyEnd = LocalDateTime.parse(currentDate + " 19:59:59", DATE_TIME_FORMATTER);

            boolean nightShift = LocalDateTime.now().getHour() < 12;
            LocalDateTime reportFrom = nightShift ? prevDutyBegin : currDutyBegin;
            LocalDateTime reportTo   = nightShift ? prevDutyEnd   : currDutyEnd;

            // Parallel I/O: IMAP + Zabbix login + Debtors run concurrently
            Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> msgLogGroup = null;
            ZabbixClient zabbix = null;
            String debtorsHtml = "";

            try (var ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

                CompletableFuture<Map<String, Map<String, Map<Long, Map<Long, List<String>>>>>> imapFuture;
                if (config.isIncidentsEnabled()) {
                    imapFuture = CompletableFuture.supplyAsync(() -> {
                        try {
                            return new Client(config).prepareImapFolder(
                                    isInteractive, prevDutyBegin, prevDutyEnd, currDutyBegin, currDutyEnd);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, ioExecutor);
                } else {
                    imapFuture = CompletableFuture.completedFuture(null);
                }

                CompletableFuture<ZabbixClient> zabbixFuture;
                if (config.isZabbixEnabled()) {
                    zabbixFuture = CompletableFuture.supplyAsync(() -> {
                        ZabbixClient zc = new ZabbixClient(config);
                        if (zc.login()) {
                            return zc;
                        }
                        log.warn("Zabbix: login failed, graphs disabled");
                        return null;
                    }, ioExecutor);
                } else {
                    zabbixFuture = CompletableFuture.completedFuture(null);
                }

                CompletableFuture<String> debtorsFuture;
                if (!nightShift && config.isDebtorsEnabled()) {
                    debtorsFuture = CompletableFuture.supplyAsync(
                            () -> new Debtors(config).toString(), ioExecutor);
                } else {
                    debtorsFuture = CompletableFuture.completedFuture("");
                }

                try {
                    CompletableFuture.allOf(imapFuture, zabbixFuture, debtorsFuture).join();
                } catch (CompletionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re && re.getCause() instanceof MessagingException me) {
                        throw me;
                    }
                    if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
                        throw ioe;
                    }
                    if (cause instanceof MessagingException me) {
                        throw me;
                    }
                    throw new IOException("Initialization failed: " + cause.getMessage(), cause);
                }

                msgLogGroup = imapFuture.join();
                zabbix = zabbixFuture.join();
                debtorsHtml = debtorsFuture.join();
            }

            String subject;
            StringBuilder message = new StringBuilder(
                "<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\"><style>"
                + "body{font-family:Arial,sans-serif;font-size:13px;background:#f0f2f5;color:#222;margin:0;padding:16px}"
                + "h1{font-size:16px;color:#1a1a2e;margin:8px 0 4px}"
                + "h2{font-size:13px;color:#16213e;margin:24px 0 6px;background:#e8eaf0;padding:5px 10px;border-left:4px solid #37474f}"
                + "table{border-collapse:collapse;background:#fff;box-shadow:2px 2px 6px rgba(0,0,0,.2);margin-bottom:8px}"
                + "th{background:#37474f;color:#fff;padding:6px 10px;text-align:left;font-size:12px;border:1px solid #546e7a}"
                + "td{padding:5px 10px;border:1px solid #cfd8dc;vertical-align:top;font-size:12px}"
                + "tr:nth-child(even) td{background:#f5f7fa}"
                + "tr.row-start td{background:#fff0f0}"
                + "tr.row-end td{background:#f0fff0}"
                + "tr.row-critical td{background:#fff0f0}"
                + "tr.row-start:nth-child(even) td{background:#f5e2e2}"
                + "tr.row-end:nth-child(even) td{background:#e2f5e2}"
                + "tr.row-critical:nth-child(even) td{background:#f5e2e2}"
                + "tr:hover td{background:#e8ecf5!important}"
                + ".section{margin-bottom:20px}"
                + ".table-debtors{box-shadow:0 0 0 2px #ef9a9a,2px 2px 6px rgba(0,0,0,.2)}"
                + "</style></head><body>");

            if (nightShift) {
                subject = "Автоматизований звіт за період з " + prevDutyBegin.format(DATE_TIME_FORMATTER) + " по " + prevDutyEnd.format(DATE_TIME_FORMATTER);
                if (config.isIncidentsEnabled() && msgLogGroup != null) {
                    message.append(Client.formatReport(config, prevDutyBegin, prevDutyEnd, msgLogGroup, zabbix));
                }
            } else {
                subject = "Автоматизований звіт за період з " + currDutyBegin.format(DATE_TIME_FORMATTER) + " по " + currDutyEnd.format(DATE_TIME_FORMATTER);
                if (config.isIncidentsEnabled() && msgLogGroup != null) {
                    message.append(Client.formatReport(config, currDutyBegin, currDutyEnd, msgLogGroup, zabbix));
                }
                message.append(debtorsHtml);
            }

            if (config.isTemperatureEnabled() || config.isRamosEnabled()) {
                SnmpClient snmpClient = new SnmpClient(config);
                if (config.isTemperatureEnabled()) {
                    message.append(snmpClient.getCelsius(reportFrom, reportTo, zabbix));
                }
                if (config.isRamosEnabled()) {
                    message.append(snmpClient.getRamos());
                }
            }

            message.append("</body></html>");

            EmailSender emailSender = new EmailSender(config);
            emailSender.sendReport(subject, message.toString());

        } catch (MessagingException | IOException e) {
            log.error("Fatal error: {}", e.getMessage());
            System.exit(1);
        }
    }
}
