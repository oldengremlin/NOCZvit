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

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * NOC report on incidents registered automatically by Zabbix and OSM systems
 *
 * @author olden
 */
public class NOCZvit {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        try {
            Config config = new Config(args);
            if (!config.isValid()) {
                System.err.println("Invalid configuration. Ensure all required email properties are set.");
                System.exit(1);
            }

            // Check if all sections are disabled
            if (!config.isIncidentsEnabled() && !config.isTemperatureEnabled() && !config.isRamosEnabled() && !config.isDebtorsEnabled()) {
                System.err.println("All report sections are disabled, skipping email sending.");
                return;
            }

            boolean isInteractive = System.console() != null;
            LocalDate currentDate = LocalDate.now();
            LocalDate yesterday = currentDate.minusDays(1);

            LocalDateTime prevDutyBegin = LocalDateTime.parse(yesterday + " 20:00:00", DATE_TIME_FORMATTER);
            LocalDateTime prevDutyEnd = LocalDateTime.parse(currentDate + " 07:59:59", DATE_TIME_FORMATTER);
            LocalDateTime currDutyBegin = LocalDateTime.parse(currentDate + " 08:00:00", DATE_TIME_FORMATTER);
            LocalDateTime currDutyEnd = LocalDateTime.parse(currentDate + " 19:59:59", DATE_TIME_FORMATTER);

            Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> msgLogGroup = null;
            if (config.isIncidentsEnabled()) {
                ImapClient imapClient = new ImapClient(config);
                msgLogGroup = imapClient.prepareImapFolder(isInteractive, prevDutyBegin, prevDutyEnd, currDutyBegin, currDutyEnd);
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

            if (LocalDateTime.now().getHour() < 12) {
                subject = "Автоматизований звіт за період з " + prevDutyBegin.format(DATE_TIME_FORMATTER) + " по " + prevDutyEnd.format(DATE_TIME_FORMATTER);
                if (config.isIncidentsEnabled() && msgLogGroup != null) {
                    message.append(ImapClient.formatReport(config, prevDutyBegin, prevDutyEnd, msgLogGroup));
                }
            } else {
                subject = "Автоматизований звіт за період з " + currDutyBegin.format(DATE_TIME_FORMATTER) + " по " + currDutyEnd.format(DATE_TIME_FORMATTER);
                if (config.isIncidentsEnabled() && msgLogGroup != null) {
                    message.append(ImapClient.formatReport(config, currDutyBegin, currDutyEnd, msgLogGroup));
                }
                if (config.isDebtorsEnabled()) {
                    message.append(new Debtors(config));
                }
            }

            if (config.isTemperatureEnabled() || config.isRamosEnabled()) {
                SnmpClient snmpClient = new SnmpClient(config);
                if (config.isTemperatureEnabled()) {
                    message.append(snmpClient.getCelsius());
                }
                if (config.isRamosEnabled()) {
                    message.append(snmpClient.getRamos());
                }
            }

            message.append("</body></html>");

            EmailSender emailSender = new EmailSender(config);
            emailSender.sendReport(subject, message.toString());

        } catch (MessagingException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
