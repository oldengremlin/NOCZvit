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
package net.ukrcom.noczvit.snmp;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.apache.commons.text.StringEscapeUtils;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.ConcurrentPoll;
import net.ukrcom.noczvit.imap.DateUtils;

/**
 * Polls SNMP-capable devices for temperature data (Celsius section) and Ramos environmental
 * sensors, then renders the results as HTML report sections.
 *
 * <p>All host queries run concurrently via virtual threads, bounded by a semaphore of
 * {@value #MAX_CONCURRENT_SNMP} to avoid overwhelming the network. Zabbix temperature graphs
 * are optionally embedded as inline PNG images when a {@link net.ukrcom.noczvit.zabbix.Client}
 * is provided.
 */
@Slf4j
public class Client {

    private static final int MAX_CONCURRENT_SNMP = 10;

    /**
     * Intermediate result for one Celsius host query.
     *
     * @param cells    one or more {@code <td>} fragments for the table row
     * @param graphRow optional {@code <tr>} row containing the embedded temperature graph
     */
    private record CelsiusResult(String cells, String graphRow) { }

    private final Config config;

    /** Creates the SNMP client bound to the given configuration. */
    public Client(Config config) {
        this.config = config;
    }

    /**
     * Polls all configured SNMP hosts for temperature data and returns an HTML section.
     *
     * @param from   report period start (passed through to Zabbix for graph time range)
     * @param to     report period end
     * @param zabbix Zabbix client for temperature graphs; {@code null} to skip graphs
     * @return HTML fragment containing the temperature table (never null)
     */
    public String getCelsius(LocalDateTime from, LocalDateTime to, net.ukrcom.noczvit.zabbix.Client zabbix) {
        StringBuilder html = new StringBuilder();
        html.append("<h2 class=\"temp-title\">Температура обладнання на виносах, станом на ")
                .append(DateUtils.formatUa(LocalDateTime.now()))
                .append("</h2>\n")
                .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                .append("<thead><tr>")
                .append("<th style=\"width:30px\">№</th>")
                .append("<th>Обладнання</th>")
                .append("<th>Компонент</th>")
                .append("<th>Температура</th>")
                .append("</tr></thead><tbody>\n");

        List<String> hostnames = new ArrayList<>(config.getHosts().keySet());
        Collections.sort(hostnames);

        List<CelsiusResult> results = ConcurrentPoll.run(hostnames,
                hostname -> queryHostCelsius(hostname, from, to, zabbix), MAX_CONCURRENT_SNMP, "celsius");

        int n = 0;
        for (CelsiusResult result : results) {
            n++;
            html.append("<tr><td>").append(n).append(".</td>")
                    .append(result.cells()).append("</tr>\n");
            html.append(result.graphRow());
        }

        html.append("</tbody></table>\n");
        return html.toString();
    }

    /**
     * Performs a single SNMPv2c GET for one host, returning the component description, temperature
     * value, and an optional Zabbix graph row. Returns an error row on any failure.
     */
    private CelsiusResult queryHostCelsius(String hostname, LocalDateTime from, LocalDateTime to, net.ukrcom.noczvit.zabbix.Client zabbix) {
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
                log.warn("SNMP celsius {}: {}", host + "." + domain, error);
                return new CelsiusResult(
                        "<td><b>" + StringEscapeUtils.escapeHtml4(host) + "</b></td>"
                        + "<td colspan=\"2\"><i>не вдалося отримати доступ: "
                        + StringEscapeUtils.escapeHtml4(error) + "</i></td>",
                        ""
                );
            }

            String desc = response.getVariable(new OID(config.getHosts().get(hostname).get("desc"))).toString();
            String temp = response.getVariable(new OID(config.getHosts().get(hostname).get("temp"))).toString();

            log.debug("{} -> {} -> {}", host + "." + domain, config.getHosts().get(hostname).get("desc"), desc);
            log.debug("{} -> {} -> {}", host + "." + domain, config.getHosts().get(hostname).get("temp"), temp);

            return new CelsiusResult(
                    "<td><b>" + StringEscapeUtils.escapeHtml4(host + "." + domain) + "</b></td>"
                    + "<td>" + StringEscapeUtils.escapeHtml4(desc) + "</td>"
                    + "<td><b>" + StringEscapeUtils.escapeHtml4(temp) + "</b>°C</td>",
                    (zabbix != null) ? zabbix.getGraphRow(host, desc, from, to) : ""
            );
        } catch (IOException e) {
            log.warn("SNMP celsius {}: {}", host, e.getMessage());
            return new CelsiusResult(
                    "<td><b>" + StringEscapeUtils.escapeHtml4(host) + "</b></td>"
                    + "<td colspan=\"2\"><i>не вдалося отримати доступ: "
                    + StringEscapeUtils.escapeHtml4(e.getMessage()) + "</i></td>",
                    ""
            );
        }
    }

    /**
     * Polls all configured Ramos environmental sensors via SNMPv2c GETNEXT walk and returns
     * an HTML section with per-sensor temperature readings and warning/critical colour coding.
     *
     * @return HTML fragment containing Ramos sensor tables (never null)
     */
    public String getRamos() {
        StringBuilder html = new StringBuilder();
        html.append("<p>\n<h1>Температурні показники Ramos, станом на ")
                .append(DateUtils.formatUa(LocalDateTime.now()))
                .append("</h1>\n");

        List<String> hosts = new ArrayList<>(config.getRamos().keySet());
        Collections.sort(hosts);

        ConcurrentPoll.run(hosts, this::queryHostRamos, MAX_CONCURRENT_SNMP, "ramos").forEach(html::append);

        return html.toString();
    }

    /**
     * Walks the Ramos temperature-sensor MIB subtree for one host using GETNEXT and assembles
     * an HTML table section with sensor descriptions and colour-coded readings.
     */
    private String queryHostRamos(String host) {
        StringBuilder fragment = new StringBuilder();
        fragment.append("<div class=\"section\">\n")
                .append("<h2>Майданчик ")
                .append(StringEscapeUtils.escapeHtml4(config.getRamos().get(host).get("name")))
                .append("</h2>\n")
                .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
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
                    log.warn("SNMP ramos {}: {}", host, error);
                    fragment.append("<tr><td colspan=\"3\"><i>").append(host)
                            .append(" - не вдалося отримати доступ: ").append(error).append("</i></td></tr>\n");
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

                log.debug("{} = {} : desc={}, unit={}, value={}, lw={}, hw={}, lc={}, hc={}",
                        oid, sensorIndex, desc, unit, value, lw, hw, lc, hc);

                // escape before injecting <font> markers, otherwise they would be escaped too
                if (desc != null) {
                    desc = StringEscapeUtils.escapeHtml4(desc)
                            .replaceAll("(?i)(hot\\s*zone)", "<font color=darkred>$1</font>")
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
                        .append("<b>").append(value != null ? StringEscapeUtils.escapeHtml4(value) : "?")
                        .append("</b>°").append(unit != null ? StringEscapeUtils.escapeHtml4(unit) : "")
                        .append("</td>")
                        .append("</tr>\n");

                pdu.clear();
                pdu.add(new VariableBinding(oid));
            }
        } catch (IOException e) {
            log.warn("SNMP ramos {}: {}", host, e.getMessage());
            fragment.append("<tr><td colspan=\"3\"><i>").append(host)
                    .append(" - не вдалося отримати доступ: ").append(e.getMessage()).append("</i></td></tr>\n");
        }

        fragment.append("</tbody></table>\n</div>\n");
        return fragment.toString();
    }

    /**
     * Safely extracts a string value for {@code oid} from a PDU response.
     * Returns {@code null} on error response, missing variable, or SNMP "noSuchObject" / "noSuchInstance".
     */
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
