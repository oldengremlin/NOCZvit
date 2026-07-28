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

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;

public class SnmpClient {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_CONCURRENT_SNMP = 10;

    private record CelsiusResult(String cells, String graphRow) {}

    private final Config config;

    public SnmpClient(Config config) {
        this.config = config;
    }

    public String getCelsius(LocalDateTime from, LocalDateTime to, ZabbixClient zabbix) {
        StringBuilder html = new StringBuilder();
        html.append("<p>\n<h1>Температура обладнання на виносах, станом на ")
                .append(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                .append("</h1>\n")
                .append("<table width=\"75%\" cellspacing=\"0\" cellpadding=\"0\">")
                .append("<thead><tr>")
                .append("<th style=\"width:30px\">№</th>")
                .append("<th>Обладнання</th>")
                .append("<th>Компонент</th>")
                .append("<th>Температура</th>")
                .append("</tr></thead><tbody>\n");

        List<String> hostnames = new ArrayList<>(config.getHosts().keySet());
        Collections.sort(hostnames);

        Semaphore sem = new Semaphore(MAX_CONCURRENT_SNMP);
        List<Future<CelsiusResult>> futures = new ArrayList<>(hostnames.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String hostname : hostnames) {
                futures.add(executor.submit(() -> {
                    sem.acquire();
                    try {
                        return queryHostCelsius(hostname, from, to, zabbix);
                    } finally {
                        sem.release();
                    }
                }));
            }
        }

        int n = 0;
        for (Future<CelsiusResult> future : futures) {
            try {
                n++;
                CelsiusResult result = future.get();
                html.append("<tr><td>").append(n).append(".</td>")
                        .append(result.cells()).append("</tr>\n");
                html.append(result.graphRow());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                if (config.isDebug()) {
                    System.err.println("ERROR (celsius future): " + e.getCause().getMessage());
                }
            }
        }

        html.append("</tbody></table>\n");
        return html.toString();
    }

    private CelsiusResult queryHostCelsius(String hostname, LocalDateTime from, LocalDateTime to, ZabbixClient zabbix) {
        String host = hostname.split(" ")[0];
        String domain = config.getSnmpHostsSuffix();

        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();

            CommunityTarget<Address> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(config.getSnmpCommunityCelsius()));
            target.setAddress(new UdpAddress(host + "." + domain + "/161"));
            target.setVersion(SnmpConstants.version2c);
            target.setTimeout(5000);
            target.setRetries(2);

            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(config.getHosts().get(hostname).get("desc"))));
            pdu.add(new VariableBinding(new OID(config.getHosts().get(hostname).get("temp"))));
            pdu.setType(PDU.GET);

            PDU response = snmp.send(pdu, target).getResponse();
            if (response == null || response.getErrorStatus() != PDU.noError) {
                String error = response != null ? response.getErrorStatusText() : "Timeout";
                if (config.isDebug()) {
                    System.err.println("ERROR: " + error);
                }
                return new CelsiusResult(
                    "<td><b>" + host + "</b></td>"
                    + "<td colspan=\"2\"><i>не вдалося отримати доступ: " + error + "</i></td>",
                    ""
                );
            }

            String desc = response.getVariable(new OID(config.getHosts().get(hostname).get("desc"))).toString();
            String temp = response.getVariable(new OID(config.getHosts().get(hostname).get("temp"))).toString();

            if (config.isDebug()) {
                System.err.printf("%s -> %s -> %s%n", host + "." + domain, config.getHosts().get(hostname).get("desc"), desc);
                System.err.printf("%s -> %s -> %s%n", host + "." + domain, config.getHosts().get(hostname).get("temp"), temp);
            }

            String graphRow = (zabbix != null) ? zabbix.getGraphRow(host, desc, from, to) : "";
            return new CelsiusResult(
                "<td><b>" + host + "." + domain + "</b></td>"
                + "<td>" + desc + "</td>"
                + "<td><b>" + temp + "</b>°C</td>",
                graphRow
            );
        } catch (IOException e) {
            if (config.isDebug()) {
                System.err.println("ERROR: " + e.getMessage());
            }
            return new CelsiusResult(
                "<td><b>" + host + "</b></td>"
                + "<td colspan=\"2\"><i>не вдалося отримати доступ: " + e.getMessage() + "</i></td>",
                ""
            );
        }
    }

    public String getRamos() {
        StringBuilder html = new StringBuilder();
        html.append("<p>\n<h1>Температурні показники Ramos, станом на ")
                .append(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                .append("</h1>\n");

        List<String> hosts = new ArrayList<>(config.getRamos().keySet());
        Collections.sort(hosts);

        Semaphore sem = new Semaphore(MAX_CONCURRENT_SNMP);
        List<Future<String>> futures = new ArrayList<>(hosts.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String host : hosts) {
                futures.add(executor.submit(() -> {
                    sem.acquire();
                    try {
                        return queryHostRamos(host);
                    } finally {
                        sem.release();
                    }
                }));
            }
        }

        for (Future<String> future : futures) {
            try {
                html.append(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                if (config.isDebug()) {
                    System.err.println("ERROR (ramos future): " + e.getCause().getMessage());
                }
            }
        }

        return html.toString();
    }

    private String queryHostRamos(String host) {
        StringBuilder fragment = new StringBuilder();
        fragment.append("<div class=\"section\">\n")
                .append("<h2>Майданчик ").append(config.getRamos().get(host).get("name")).append("</h2>\n")
                .append("<table width=\"75%\" cellspacing=\"0\" cellpadding=\"0\">")
                .append("<thead><tr>")
                .append("<th style=\"width:30px\">№</th>")
                .append("<th>Датчик</th>")
                .append("<th>Показник</th>")
                .append("</tr></thead><tbody>\n");

        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();

            CommunityTarget<Address> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(config.getSnmpCommunityRamos()));
            target.setAddress(new UdpAddress(host + "/161"));
            target.setVersion(SnmpConstants.version2c);
            target.setTimeout(5000);
            target.setRetries(2);

            String temperatureSensorIndex = config.getRamos().get(host).get("temperatureSensorIndex");
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(temperatureSensorIndex)));
            pdu.setType(PDU.GETNEXT);

            int n = 0;
            while (true) {
                PDU response = snmp.send(pdu, target).getResponse();
                if (response == null || response.getErrorStatus() != PDU.noError) {
                    String error = response != null ? response.getErrorStatusText() : "Timeout";
                    fragment.append("<tr><td colspan=\"3\"><i>").append(host)
                            .append(" - не вдалося отримати доступ: ").append(error).append("</i></td></tr>\n");
                    if (config.isDebug()) {
                        System.err.println("ERROR: " + error);
                    }
                    break;
                }

                OID oid = response.get(0).getOid();
                if (!oid.startsWith(new OID(temperatureSensorIndex))) {
                    break;
                }

                String sensorIndex = response.get(0).getVariable().toString();

                OID descOid = new OID(config.getRamos().get(host).get("temperatureSensorDescription") + "." + sensorIndex);
                OID unitOid = new OID(config.getRamos().get(host).get("temperatureSensorUnit") + "." + sensorIndex);
                OID valueOid = new OID(config.getRamos().get(host).get("temperatureSensorValue") + "." + sensorIndex);
                OID lwOid = new OID(config.getRamos().get(host).get("temperatureSensorLowWarning") + "." + sensorIndex);
                OID hwOid = new OID(config.getRamos().get(host).get("temperatureSensorHighWarning") + "." + sensorIndex);
                OID lcOid = new OID(config.getRamos().get(host).get("temperatureSensorLowCritical") + "." + sensorIndex);
                OID hcOid = new OID(config.getRamos().get(host).get("temperatureSensorHighCritical") + "." + sensorIndex);

                PDU getMulti = new PDU();
                getMulti.add(new VariableBinding(descOid));
                getMulti.add(new VariableBinding(unitOid));
                getMulti.add(new VariableBinding(valueOid));
                getMulti.add(new VariableBinding(lwOid));
                getMulti.add(new VariableBinding(hwOid));
                getMulti.add(new VariableBinding(lcOid));
                getMulti.add(new VariableBinding(hcOid));
                getMulti.setType(PDU.GET);

                var multiRe = snmp.send(getMulti, target);
                PDU multiResponse = multiRe != null ? multiRe.getResponse() : null;

                String desc = extractValue(multiResponse, descOid);
                String unit = extractValue(multiResponse, unitOid);
                String value = extractValue(multiResponse, valueOid);
                String lw = extractValue(multiResponse, lwOid);
                String hw = extractValue(multiResponse, hwOid);
                String lc = extractValue(multiResponse, lcOid);
                String hc = extractValue(multiResponse, hcOid);

                if (config.isDebug()) {
                    System.err.printf("%s = %s : desc=%s, unit=%s, value=%s, lw=%s, hw=%s, lc=%s, hc=%s%n",
                            oid, sensorIndex, desc, unit, value, lw, hw, lc, hc);
                }

                if (desc != null) {
                    desc = desc.replaceAll("(?i)(hot\\s*zone)", "<font color=darkred>$1</font>")
                            .replaceAll("(?i)(cold\\s*zone)", "<font color=darkblue>$1</font>");
                }

                String valueColor = "inherit";
                String rowClass = "";
                if (value != null && lw != null && hw != null && lc != null && hc != null) {
                    try {
                        double val = Double.parseDouble(value);
                        double lowWarn = Double.parseDouble(lw);
                        double highWarn = Double.parseDouble(hw);
                        double lowCrit = Double.parseDouble(lc);
                        double highCrit = Double.parseDouble(hc);

                        if (val >= lowWarn && val <= highWarn) {
                            valueColor = "darkgrey";
                        } else if (val >= lowCrit && val <= highCrit) {
                            valueColor = "darkred";
                            rowClass = " class=\"row-critical\"";
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }

                n++;
                fragment.append("<tr").append(rowClass).append(">")
                        .append("<td>").append(n).append(".</td>")
                        .append("<td>").append(desc != null ? desc : "").append("</td>")
                        .append("<td style=\"color:").append(valueColor).append("\">")
                        .append("<b>").append(value != null ? value : "?").append("</b>°").append(unit != null ? unit : "").append("</td>")
                        .append("</tr>\n");

                pdu.clear();
                pdu.add(new VariableBinding(oid));
            }
        } catch (IOException e) {
            fragment.append("<tr><td colspan=\"3\"><i>").append(host)
                    .append(" - не вдалося отримати доступ: ").append(e.getMessage()).append("</i></td></tr>\n");
            if (config.isDebug()) {
                System.err.println("ERROR: " + e.getMessage());
            }
        }

        fragment.append("</tbody></table>\n</div>\n");
        return fragment.toString();
    }

    private String extractValue(PDU response, OID oid) {
        if (response == null || response.getErrorStatus() != PDU.noError) {
            return null;
        }
        var variable = response.getVariable(oid);
        if (variable == null) {
            return null;
        }
        String s = variable.toString();
        return "noSuchObject".equals(s) || "noSuchInstance".equals(s) ? null : s;
    }
}
