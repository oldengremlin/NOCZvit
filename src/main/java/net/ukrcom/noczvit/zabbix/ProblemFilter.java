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
package net.ukrcom.noczvit.zabbix;

import java.util.List;
import java.util.regex.Pattern;
import net.ukrcom.noczvit.model.Incident;

/**
 * Фільтрує та дедублікує події Zabbix відносно до переліку інцидентів IMAP.
 *
 * <p>
 * IMAP-папка є основним джерелом даних. Zabbix — вторинним. Виключаємо:
 * <ul>
 * <li>події без хоста (порожній рядок — тригер на шаблоні або API не повернув
 * хост)</li>
 * <li>хост SDH-OSM (події OSM вже присутні через OsmIncidentParser)</li>
 * <li>проблеми "No SNMP data collection" (технічний шум моніторингу)</li>
 * <li>OSPF-події (вже надходять через OspfIncidentParser)</li>
 * <li>події перезавантаження (вже надходять через PdIncidentParser)</li>
 * <li>події, що збігаються за хостом і часом (~5 хв) з IMAP-інцидентом</li>
 * </ul>
 */
public class ProblemFilter {

    private static final Pattern OSPF = Pattern.compile("(?i)ospf");
    private static final Pattern RESTART = Pattern.compile("(?i)(restarted|rebooted)");
    private static final Pattern NO_SNMP = Pattern.compile("(?i)no snmp data collection");

    // Допустиме розходження у секундах між часом події Zabbix і часом IMAP-інциденту
    private static final long DEDUP_TOLERANCE_SEC = 300;

    private ProblemFilter() {
    }

    /**
     * Повертає відфільтрований список подій, що не дублюються в IMAP.
     *
     * @param problems повний список подій Zabbix за зміну
     * @param imapIncidents інциденти, вже присутні з IMAP-джерела
     * @return відфільтрований список
     */
    public static List<ZabbixProblem> filter(List<ZabbixProblem> problems, List<Incident> imapIncidents) {
        return problems.stream()
                .filter(p -> !p.host().isBlank())
                .filter(p -> !"SDH-OSM".equals(p.host()))
                .filter(p -> !NO_SNMP.matcher(p.name()).find())
                .filter(p -> !OSPF.matcher(p.name()).find())
                .filter(p -> !RESTART.matcher(p.name()).find())
                .filter(p -> !isDuplicateOfImap(p, imapIncidents))
                .toList();
    }

    /**
     * Returns {@code true} when an IMAP incident for the same device exists within
     * {@value #DEDUP_TOLERANCE_SEC} seconds of the Zabbix problem's start time.
     */
    private static boolean isDuplicateOfImap(ZabbixProblem problem, List<Incident> imapIncidents) {
        if (problem.host().isBlank()) {
            return false;
        }
        return imapIncidents.stream().anyMatch(inc
                -> !inc.device().isEmpty()
                && inc.device().equalsIgnoreCase(problem.host())
                && Math.abs(inc.eventTs() - problem.clock()) <= DEDUP_TOLERANCE_SEC
        );
    }
}
