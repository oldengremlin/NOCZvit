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
    private static final String TABLE_STYLE = "width=\"75%\" cellspacing=\"0\" cellpadding=\"5\" border=\"1\" "
            + "style=\"border-collapse: collapse; box-shadow: 2px 3px 8px rgba(0,0,0,0.25); margin: 10px 0;\"";

    private final Config config;

    public SnmpClient(Config config) {
        this.config = config;
    }

    public String getCelsius() {
        StringBuilder html = new StringBuilder();
        html.append("<p><table ").append(TABLE_STYLE).append(">")
                .append("<caption><h1><small><small>Температура обладнання на виносах, станом на ")
                .append(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                .append("</small></small></h1></caption><tbody>\n");

        List<String> hostnames = new ArrayList<>(config.getHosts().keySet());
        Collections.sort(hostnames);

        Semaphore sem = new Semaphore(MAX_CONCURRENT_SNMP);
        List<Future<String>> futures = new ArrayList<>(hostnames.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String hostname : hostnames) {
                futures.add(executor.submit(() -> {
                    sem.acquire();
                    try {
                        return queryHostCelsius(hostname);
                    } finally {
                        sem.release();
                    }
                }));
            }
        }

        int n = 0;
        for (Future<String> future : futures) {
            try {
                n++;
                html.append("<tr><td valign=\"top\" style=\"width: 30px;\">").append(n)
                        .append(".</td>").append(future.get()).append("</tr>\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                if (config.isDebug()) {
                    System.err.println("ERROR (celsius future): " + e.getCause().getMessage());
                }
            }
        }

        html.append("</tbody></table><p>");
        return html.toString();
    }

    private String queryHostCelsius(String hostname) {
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
                return "<td valign=\"top\"><b>" + host + "</b></td>"
                     + "<td valign=\"top\" colspan=\"2\"><i>не вдалося отримати доступ: " + error + "</i></td>";
            }

            String desc = response.getVariable(new OID(config.getHosts().get(hostname).get("desc"))).toString();
            String temp = response.getVariable(new OID(config.getHosts().get(hostname).get("temp"))).toString();

            if (config.isDebug()) {
                System.err.printf("%s -> %s -> %s%n", host + "." + domain, config.getHosts().get(hostname).get("desc"), desc);
                System.err.printf("%s -> %s -> %s%n", host + "." + domain, config.getHosts().get(hostname).get("temp"), temp);
            }

            return "<td valign=\"top\"><b>" + host + "." + domain + "</b></td>"
                 + "<td valign=\"top\">" + desc + "</td>"
                 + "<td valign=\"top\"><b>" + temp + "</b>°C</td>";
        } catch (IOException e) {
            if (config.isDebug()) {
                System.err.println("ERROR: " + e.getMessage());
            }
            return "<td valign=\"top\"><b>" + host + "</b></td>"
                 + "<td valign=\"top\" colspan=\"2\"><i>не вдалося отримати доступ: " + e.getMessage() + "</i></td>";
        }
    }

    public String getRamos() {
        StringBuilder html = new StringBuilder();
        html.append("<p>");

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

        html.append("<p>");
        return html.toString();
    }

    private String queryHostRamos(String host) {
        StringBuilder fragment = new StringBuilder();
        fragment.append("<table ").append(TABLE_STYLE).append(">")
                .append("<caption><h2>Майданчик ").append(config.getRamos().get(host).get("name")).append("</h2></caption>")
                .append("<tbody>\n");

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

                String desc = getValue(snmp, target, config.getRamos().get(host).get("temperatureSensorDescription") + "." + sensorIndex);
                String unit = getValue(snmp, target, config.getRamos().get(host).get("temperatureSensorUnit") + "." + sensorIndex);
                String value = getValue(snmp, target, config.getRamos().get(host).get("temperatureSensorValue") + "." + sensorIndex);
                String lw = getValue(snmp, target, config.getRamos().get(host).get("temperatureSensorLowWarning") + "." + sensorIndex);
                String hw = getValue(snmp, target, config.getRamos().get(host).get("temperatureSensorHighWarning") + "." + sensorIndex);
                String lc = getValue(snmp, target, config.getRamos().get(host).get("temperatureSensorLowCritical") + "." + sensorIndex);
                String hc = getValue(snmp, target, config.getRamos().get(host).get("temperatureSensorHighCritical") + "." + sensorIndex);

                if (config.isDebug()) {
                    System.err.printf("%s = %s : desc=%s, unit=%s, value=%s, lw=%s, hw=%s, lc=%s, hc=%s%n",
                            oid, sensorIndex, desc, unit, value, lw, hw, lc, hc);
                }

                desc = desc.replaceAll("(?i)(hot\\s*zone)", "<font color=darkred>$1</font>")
                        .replaceAll("(?i)(cold\\s*zone)", "<font color=darkblue>$1</font>");

                String valueColor = "inherit";
                String rowStyle = "";
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
                        rowStyle = " style=\"background-color: #fff0f0;\"";
                    }
                } catch (NumberFormatException ignored) {
                }

                n++;
                fragment.append("<tr").append(rowStyle).append(">")
                        .append("<td valign=\"top\" style=\"width: 30px;\">").append(n).append(".</td>")
                        .append("<td valign=\"top\">").append(desc).append("</td>")
                        .append("<td valign=\"top\" style=\"color: ").append(valueColor).append("; white-space: nowrap;\">")
                        .append("<b>").append(value).append("</b>°").append(unit).append("</td>")
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

        fragment.append("</tbody></table>\n");
        return fragment.toString();
    }

    private String getValue(Snmp snmp, CommunityTarget<Address> target, String oid) throws IOException {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(new OID(oid)));
        pdu.setType(PDU.GET);

        PDU response = snmp.send(pdu, target).getResponse();
        if (response != null && response.getErrorStatus() == PDU.noError) {
            return response.getVariable(new OID(oid)).toString();
        }
        return null;
    }
}
