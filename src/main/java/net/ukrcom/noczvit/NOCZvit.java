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

import net.ukrcom.noczvit.claude.SummaryClient;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.report.IncidentSectionBuilder;
import net.ukrcom.noczvit.smtp.EmailSender;
import net.ukrcom.noczvit.trap.EmersonTrapParser;
import net.ukrcom.noczvit.trap.EmersonTrapSection;
import net.ukrcom.noczvit.trap.ImapTrapReader;
import net.ukrcom.noczvit.trap.RamosTrapEvent;
import net.ukrcom.noczvit.trap.RamosTrapParser;
import net.ukrcom.noczvit.trap.RamosTrapSection;
import net.ukrcom.noczvit.trap.TrapCorrelator;
import net.ukrcom.noczvit.trap.TrapDeduplicator;
import net.ukrcom.noczvit.trap.TrapEvent;
import net.ukrcom.noczvit.trap.TrapIncident;
import net.ukrcom.noczvit.zabbix.ProblemFilter;
import net.ukrcom.noczvit.zabbix.ZabbixIncidentConverter;
import net.ukrcom.noczvit.zabbix.ZabbixProblem;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the NOC automated report generator.
 *
 * <p>Collects incidents from three sources in parallel (IMAP, Zabbix API, Debtors DB),
 * merges them into a unified incident list, builds an HTML report, and sends it by email.
 * Claude AI summary is generated from the merged incident list when enabled.
 *
 * @author olden
 */
@Slf4j
public class NOCZvit {

    /**
     * ISO pattern used only to <em>parse</em> the duty-period boundaries below. Everything the
     * report displays goes through {@link DateUtils#formatUa} instead, so all dates read the
     * same regardless of source.
     */
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Upper bound for the whole parallel init phase; well above any healthy run. */
    private static final int INIT_TIMEOUT_MINUTES = 10;

    /**
     * Application entry point.
     *
     * <p>Parses CLI arguments, initialises configuration, fetches IMAP incidents and Zabbix
     * events in parallel, converts and merges them, builds the HTML report sections, and
     * sends the result via sendmail (or SMTP in debug mode).
     *
     * @param args CLI arguments (see {@code help.txt} for the full list)
     * @throws MessagingException if a fatal IMAP or SMTP error occurs
     * @throws IOException        if configuration or dictionary files cannot be read
     */
    public static void main(String[] args) throws MessagingException, IOException {
        // Set log level to DEBUG before Config is instantiated so all initialization is visible
        if (Arrays.asList(args).contains("--debug")) {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.DEBUG);
        }

        try {
            Config config = new Config(args);
            Dictionary dictionary = new Dictionary(config);

            if (!config.isValid()) {
                log.error("Invalid configuration. Ensure all required email properties are set.");
                System.exit(1);
            }

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
            LocalDateTime reportTo = nightShift ? prevDutyEnd : currDutyEnd;

            List<Incident> incidents = null;
            net.ukrcom.noczvit.zabbix.Client zabbix = null;
            String debtorsHtml = "";
            List<ZabbixProblem> zabbixProblems = Collections.emptyList();
            EmersonTrapSection.SectionResult trapResult = new EmersonTrapSection.SectionResult("", "", "");
            RamosTrapSection.SectionResult ramosTrapResult = new RamosTrapSection.SectionResult("", "");

            try (var ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

                CompletableFuture<List<Incident>> imapFuture;
                if (config.isIncidentsEnabled()) {
                    imapFuture = CompletableFuture.supplyAsync(
                            () -> new net.ukrcom.noczvit.imap.Client(config, dictionary).prepareImapFolder(
                                    isInteractive, prevDutyBegin, prevDutyEnd, currDutyBegin, currDutyEnd),
                            ioExecutor);
                } else {
                    imapFuture = CompletableFuture.completedFuture(null);
                }

                CompletableFuture<net.ukrcom.noczvit.zabbix.Client> zabbixFuture;
                if (config.isZabbixEnabled()) {
                    zabbixFuture = CompletableFuture.supplyAsync(() -> {
                        net.ukrcom.noczvit.zabbix.Client zc = new net.ukrcom.noczvit.zabbix.Client(config);
                        if (zc.login()) {
                            return zc;
                        }
                        log.warn("Zabbix: login failed, graphs disabled");
                        return null;
                    }, ioExecutor);
                } else {
                    zabbixFuture = CompletableFuture.completedFuture(null);
                }

                // Завантажуємо Zabbix-події після логіну (якщо Zabbix недоступний — порожній список).
                CompletableFuture<List<ZabbixProblem>> zabbixProblemsFuture;
                if (config.isZabbixEnabled() && config.isIncidentsEnabled()) {
                    zabbixProblemsFuture = zabbixFuture.thenApplyAsync(zc -> {
                        if (zc == null) {
                            return Collections.<ZabbixProblem>emptyList();
                        }
                        return zc.getProblems(reportFrom, reportTo);
                    }, ioExecutor);
                } else {
                    zabbixProblemsFuture = CompletableFuture.completedFuture(Collections.emptyList());
                }

                CompletableFuture<String> debtorsFuture;
                if (!nightShift && config.isDebtorsEnabled()) {
                    debtorsFuture = CompletableFuture.supplyAsync(
                            () -> new Debtors(config).toString(), ioExecutor);
                } else {
                    debtorsFuture = CompletableFuture.completedFuture("");
                }

                final long fromEpoch = reportFrom.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
                final long toEpoch = reportTo.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
                final java.time.Instant trapFrom = java.time.Instant.ofEpochSecond(fromEpoch);
                final java.time.Instant trapTo   = java.time.Instant.ofEpochSecond(toEpoch);

                CompletableFuture<EmersonTrapSection.SectionResult> trapFuture;
                if (config.isTrapEnabled()) {
                    trapFuture = CompletableFuture.supplyAsync(() -> {
                        try {
                            ImapTrapReader reader = new ImapTrapReader(config);
                            List<TrapEvent> events = EmersonTrapParser.parse(
                                    reader.readTraps(isInteractive, fromEpoch, toEpoch));
                            // Filter by body timestamp — works in both fetchAll and date modes
                            events = events.stream()
                                    .filter(e -> !e.timestamp().isBefore(trapFrom)
                                              && !e.timestamp().isAfter(trapTo))
                                    .toList();
                            events = TrapDeduplicator.deduplicate(events, config.getSnmpTrapDedupSeconds());
                            TrapCorrelator.CorrelationResult corr = new TrapCorrelator(
                                    config.getSnmpTrapColdstartLinkMinutes()).correlate(events);
                            return new EmersonTrapSection().build(corr.incidents(), corr.unknownTraps());
                        } catch (MessagingException e) {
                            log.warn("ImapTrapReader: IMAP error: {}", e.getMessage());
                            return new EmersonTrapSection.SectionResult("", "", "");
                        }
                    }, ioExecutor);
                } else {
                    trapFuture = CompletableFuture.completedFuture(new EmersonTrapSection.SectionResult("", "", ""));
                }

                CompletableFuture<RamosTrapSection.SectionResult> ramosTrapFuture;
                if (config.isRamosTrapEnabled()) {
                    ramosTrapFuture = CompletableFuture.supplyAsync(() -> {
                        try {
                            ImapTrapReader reader = new ImapTrapReader(config);
                            List<RamosTrapEvent> events = RamosTrapParser.parse(
                                    reader.readTrapsFromFolder(isInteractive, fromEpoch, toEpoch,
                                            config.getRamosTrapFolder()));
                            events = events.stream()
                                    .filter(e -> !e.timestamp().isBefore(trapFrom)
                                              && !e.timestamp().isAfter(trapTo))
                                    .toList();
                            return new RamosTrapSection().build(events);
                        } catch (MessagingException e) {
                            log.warn("RamosTrapParser: IMAP error: {}", e.getMessage());
                            return new RamosTrapSection.SectionResult("", "");
                        }
                    }, ioExecutor);
                } else {
                    ramosTrapFuture = CompletableFuture.completedFuture(new RamosTrapSection.SectionResult("", ""));
                }

                try {
                    // Safety net: per-protocol timeouts are set in each client, but a bug there
                    // would otherwise hang the cron run forever (executor.close() waits 1 day).
                    CompletableFuture.allOf(imapFuture, zabbixProblemsFuture, debtorsFuture,
                            trapFuture, ramosTrapFuture)
                            .orTimeout(INIT_TIMEOUT_MINUTES, TimeUnit.MINUTES).join();
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

                incidents = imapFuture.join();
                zabbix = zabbixFuture.join();
                zabbixProblems = zabbixProblemsFuture.join();
                debtorsHtml = debtorsFuture.join();
                trapResult = trapFuture.join();
                ramosTrapResult = ramosTrapFuture.join();
            }

            // Конвертуємо відфільтровані Zabbix-події в Incident і зливаємо з IMAP-інцидентами.
            // incidentsForTable йде і в HTML-таблицю, і до Claude (якщо увімкнено).
            ZabbixIncidentConverter zabbixConverter = new ZabbixIncidentConverter(dictionary);
            List<Incident> zabbixIncidents = (incidents != null)
                                             ? ProblemFilter.filter(zabbixProblems, incidents).stream()
                            .flatMap(p -> zabbixConverter.convert(p).stream())
                            .toList()
                                             : Collections.emptyList();

            List<Incident> incidentsForTable = (incidents != null)
                                               ? Stream.concat(incidents.stream(), zabbixIncidents.stream()).toList()
                                               : Collections.emptyList();

            String subject;
            StringBuilder message = new StringBuilder(
                    "<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\"><style>"
                    + "body{font-family:Arial,sans-serif;font-size:13px;background:#f0f2f5;color:#222;margin:0;padding:16px}"
                    + "h1{font-size:16px;color:#1a1a2e;margin:8px 0 4px}"
                    + "h2{font-size:13px;color:#16213e;margin:24px 0 6px;background:#e8eaf0;padding:5px 10px;border-left:4px solid #37474f}"
                    + "h2.trap-title{font-size:16px;color:#1b5e20;background:#e8eaf0;border-left:4px solid #2e7d32;margin:8px 0 4px;padding:5px 10px}"
                    + "h3.trap-device{font-size:13px;color:#1b5e20;background:#e8eaf0;border-left:4px solid #2e7d32;margin:12px 0 4px;padding:5px 10px}"
                    + "h2.temp-title{font-size:16px;color:#1976d2;background:#e8eaf0;border-left:4px solid #1976d2;margin:16px 0 6px;padding:5px 10px}"
                    + "h2.trap-ps-title{font-size:14px;color:#37474f;background:#e8eaf0;border-left:4px solid #546e7a;margin:16px 0 4px;padding:5px 10px}"
                    + "h3.trap-ps-device{font-size:11px;color:#37474f;background:#e8eaf0;border-left:4px solid #546e7a;margin:8px 0 2px;padding:4px 8px}"
                    + ".trap-ps-list{font-size:11px;color:#455a64;background:#fffde7;padding:4px 8px 4px 28px;margin:0 0 4px;list-style:disc}"
                    + ".trap-ps-list li{padding:1px 0}"
                    + "h2.ramos-title{font-size:16px;color:#e65100;background:#e8eaf0;border-left:4px solid #f38120;margin:16px 0 6px;padding:5px 10px}"
                    + "h3.ramos-room{font-size:13px;color:#bf360c;background:#e8eaf0;border-left:4px solid #f38120;margin:12px 0 4px;padding:5px 10px}"
                    + "table{border-collapse:collapse;background:#fff;box-shadow:2px 2px 6px rgba(0,0,0,.2);margin-bottom:8px}"
                    + "th{background:#37474f;color:#fff;padding:6px 10px;text-align:left;font-size:12px;border:1px solid #546e7a}"
                    + "td{padding:5px 10px;border:1px solid #cfd8dc;vertical-align:top;font-size:12px}"
                    + "tr:nth-child(even) td{background:#f5f7fa}"
                    + "tr.row-critical td{background:#fff0f0}"
                    + "tr.row-critical:nth-child(even) td{background:#f5e2e2}"
                    + "tr:hover td{background:#e8ecf5!important}"
                    + ".section{margin-bottom:20px}"
                    + ".table-debtors{box-shadow:0 0 0 2px #ef9a9a,2px 2px 6px rgba(0,0,0,.2)}"
                    + "</style></head><body>");

            IncidentSectionBuilder incidentBuilder = new IncidentSectionBuilder();
            SummaryClient summaryClient = config.isClaudeEnabled() ? new SummaryClient(config) : null;

            String allTrapPlainText = trapResult.plainText()
                    + (ramosTrapResult.plainText().isBlank() ? "" : "\n" + ramosTrapResult.plainText());

            // reportFrom/reportTo already resolve the night/day period (see above) — the two
            // branches differed only in which duty pair they passed on.
            subject = "Автоматизований звіт за період з " + DateUtils.formatUa(reportFrom)
                    + " по " + DateUtils.formatUa(reportTo);
            if (config.isIncidentsEnabled() && incidents != null) {
                String summaryHtml = summaryClient != null
                                     ? summaryClient.generateSummary(incidentsForTable, reportFrom, reportTo,
                                                                      allTrapPlainText) : null;
                message.append(incidentBuilder.build(incidentsForTable, zabbix, reportFrom, reportTo, summaryHtml));
            }

            if (!trapResult.isEmpty()) {
                message.append(trapResult.html());
            }

            if (!ramosTrapResult.isEmpty()) {
                message.append(ramosTrapResult.html());
            }

            if (!nightShift) {
                message.append(debtorsHtml);
            }

            if (config.isTemperatureEnabled() || config.isRamosEnabled()) {
                net.ukrcom.noczvit.snmp.Client snmpClient = new net.ukrcom.noczvit.snmp.Client(config);
                if (config.isTemperatureEnabled()) {
                    message.append(snmpClient.getCelsius(reportFrom, reportTo, zabbix));
                }
                if (config.isRamosEnabled()) {
                    message.append(snmpClient.getRamos());
                }
            }

            if (!trapResult.unknownHtml().isBlank()) {
                message.append(trapResult.unknownHtml());
            }

            message.append("</body></html>");

            new EmailSender(config).sendReport(subject, message.toString());

        } catch (MessagingException | IOException e) {
            // full stack trace: a wrapped NPE used to print "Fatal error: null" with no context
            log.error("Fatal error", e);
            System.exit(1);
        }
    }
}
