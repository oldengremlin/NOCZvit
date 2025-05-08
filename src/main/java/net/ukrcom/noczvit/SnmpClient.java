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
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SnmpClient {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final Config config;

    public SnmpClient(Config config) {
        this.config = config;
    }

    public String getCelsius() {
        StringBuilder html = new StringBuilder();
        html.append("<p><ol><h1><small><small>Температура обладнання на виносах, станом на ")
                .append(LocalDateTime.now().format(DATE_TIME_FORMATTER)).append("</small></small></h1>");

        List<String> hostnames = new ArrayList<>(config.getHosts().keySet());
        Collections.sort(hostnames);

        for (String hostname : hostnames) {
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
                    html.append("<li style=\"margin-left: 50px;\">").append(host).append(" - не вдалося отримати доступ у зв'язку з '<b>").append(error).append("</b>'</li>");
                    if (config.isDebug()) {
                        System.err.println("ERROR: " + error);
                    }
                    continue;
                }

                String desc = response.getVariable(new OID(config.getHosts().get(hostname).get("desc"))).toString();
                String temp = response.getVariable(new OID(config.getHosts().get(hostname).get("temp"))).toString();

                if (config.isDebug()) {
                    System.err.printf("%s -> %s -> %s%n", host + "." + domain, config.getHosts().get(hostname).get("desc"), desc);
                    System.err.printf("%s -> %s -> %s%n", host + "." + domain, config.getHosts().get(hostname).get("temp"), temp);
                }

                html.append("<li style=\"margin-left: 75px;\"><b>").append(host).append(".").append(domain).append("</b> [").append(desc).append("] — <b>").append(temp).append("</b>°C</li>");
            } catch (IOException e) {
                html.append("<li style=\"margin-left: 50px;\">").append(host).append(" - не вдалося отримати доступ у зв'язку з '<b>").append(e.getMessage()).append("</b>'</li>");
                if (config.isDebug()) {
                    System.err.println("ERROR: " + e.getMessage());
                }
            }
        }

        html.append("</ol><p>");
        return html.toString();
    }

    public String getRamos() {
        StringBuilder html = new StringBuilder();
        html.append("<p><ol><h1><small><small>Температурні показники Ramos, станом на ")
                .append(LocalDateTime.now().format(DATE_TIME_FORMATTER)).append("</small></small></h1>");

        List<String> hosts = new ArrayList<>(config.getRamos().keySet());
        Collections.sort(hosts);

        for (String host : hosts) {
            try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
                snmp.listen();

                CommunityTarget<Address> target = new CommunityTarget<>();
                target.setCommunity(new OctetString(config.getSnmpCommunityRamos()));
                target.setAddress(new UdpAddress(host + "/161"));
                target.setVersion(SnmpConstants.version2c);
                target.setTimeout(5000);
                target.setRetries(2);

                html.append("<h2 style=\"margin-left: 25px;\"><small>Майданчик ").append(config.getRamos().get(host).get("name")).append("</small></h2>");

                String temperatureSensorIndex = config.getRamos().get(host).get("temperatureSensorIndex");
                PDU pdu = new PDU();
                pdu.add(new VariableBinding(new OID(temperatureSensorIndex)));
                pdu.setType(PDU.GETNEXT);

                while (true) {
                    PDU response = snmp.send(pdu, target).getResponse();
                    if (response == null || response.getErrorStatus() != PDU.noError) {
                        String error = response != null ? response.getErrorStatusText() : "Timeout";
                        html.append("<li style=\"margin-left: 50px;\">").append(host).append(" - не вдалося отримати доступ у зв'язку з '<b>").append(error).append("</b>'</li>");
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
                        System.err.printf("%s = %s : desc=%s, unit=%s, value=%s, lw=%s, hw=%s, lc=%s, hc=%s%n", oid, sensorIndex, desc, unit, value, lw, hw, lc, hc);
                    }

                    desc = desc.replaceAll("(?i)(hot\\s*zone)", "<font color=darkred>$1</font>")
                            .replaceAll("(?i)(cold\\s*zone)", "<font color=darkblue>$1</font>");

                    String color = "black";
                    try {
                        double val = Double.parseDouble(value);
                        double lowWarn = Double.parseDouble(lw);
                        double highWarn = Double.parseDouble(hw);
                        double lowCrit = Double.parseDouble(lc);
                        double highCrit = Double.parseDouble(hc);

                        if (val >= lowWarn && val <= highWarn) {
                            color = "darkgrey";
                        } else if (val >= lowCrit && val <= highCrit) {
                            color = "red";
                        }
                    } catch (NumberFormatException ignored) {
                    }

                    html.append("<li style=\"margin-left: 75px;\">").append(desc).append(" — <b><font color=\"").append(color).append("\">").append(value).append("</font></b>°").append(unit).append("</li>");

                    pdu.clear();
                    pdu.add(new VariableBinding(oid));
                }
            } catch (IOException e) {
                html.append("<li style=\"margin-left: 50px;\">").append(host).append(" - не вдалося отримати доступ у зв'язку з '<b>").append(e.getMessage()).append("</b>'</li>");
                if (config.isDebug()) {
                    System.err.println("ERROR: " + e.getMessage());
                }
            }
        }

        html.append("</ol><p>");
        return html.toString();
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
