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
import java.util.Map;

/**
 * NOC report on incidents registered automatically by Zabbix and OSM systems
 *
 * @author olden
 */
public class NOCZvit {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        try {
            Config config = new Config(args);
            if (!config.isValid()) {
                System.err.println("Invalid configuration. Ensure all required email properties are set.");
                System.exit(1);
            }

            // Check if all sections are disabled
            if (!config.isIncidentsEnabled() && !config.isTemperatureEnabled() && !config.isRamosEnabled()) {
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

            Map<String, Map<String, Map<Long, String>>> msgLogGroup = null;
            if (config.isIncidentsEnabled()) {
                ImapClient imapClient = new ImapClient(config);
                msgLogGroup = imapClient.prepareImapFolder(isInteractive, prevDutyBegin, prevDutyEnd, currDutyBegin, currDutyEnd);
            }

            String subject;
            StringBuilder message = new StringBuilder("<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\"></head><body>");

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