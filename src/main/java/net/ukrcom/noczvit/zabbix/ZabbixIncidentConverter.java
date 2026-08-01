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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;

/**
 * Перетворює {@link ZabbixProblem} на {@link Incident}-об'єкти для відображення
 * в таблиці звіту поряд з IMAP-інцидентами.
 *
 * <p>
 * Правила:
 * <ul>
 * <li>START-інцидент по {@code clock}; END-інцидент по {@code rClock} якщо
 * вирішено</li>
 * <li>Локація шукається у PD-словнику за hostname; якщо не знайдено — hostname
 * залишається як локація і додається до {@code reviewNames}</li>
 * <li>Якщо hostname починається з 'r' — у опис додається «маршрутизаторі»</li>
 * </ul>
 */
@Slf4j
public class ZabbixIncidentConverter {

    private static final Pattern TRAP_CARD_PATTERN
            = Pattern.compile("(?i)card\\s+(\\d+),\\s*port\\s+(\\d+),\\s*line\\s+(\\d+)");

    private static final String[] UA_MONTHS = {
        "", "січ", "лют", "бер", "квіт", "трав", "черв",
        "лип", "серп", "вер", "жовт", "лист", "груд"
    };

    private final Dictionary dictionary;

    public ZabbixIncidentConverter(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Конвертує одну Zabbix-проблему в один або два інциденти.
     *
     * @param p Zabbix-проблема
     * @return список з 1 (активна) або 2 (вирішена) інцидентів
     */
    public List<Incident> convert(ZabbixProblem p) {
        String host = p.host();
        String location = dictionary.lookupPD(host);
        boolean needsReview = location.equals(host);
        List<String> reviewNames = needsReview ? List.of(host) : List.of();

        String eventDesc = resolveEventDesc(host, p.name());
        String deviceWord = host.startsWith("r") ? "маршрутизаторі " : "";
        String descSuffix = eventDesc + " на " + deviceWord + location;
        String pairKey = "zabbix:" + host + ":" + p.clock();

        List<Incident> result = new ArrayList<>(2);

        result.add(buildIncident(p.clock(), location, host,
                "Zabbix зареєстровано початок інциденту, " + descSuffix,
                Status.START, reviewNames, pairKey));

        if (!p.isActive()) {
            result.add(buildIncident(p.rClock(), location, host,
                    "Zabbix зареєстровано кінець інциденту, " + descSuffix,
                    Status.END, reviewNames, pairKey));
        }

        log.debug("ZabbixIncidentConverter: {} → location='{}', needsReview={}, active={}",
                host, location, needsReview, p.isActive());
        return result;
    }

    /**
     * For adlink hosts resolves "Trap card N, port N, line N" to a human-readable
     * description via the PD dictionary (key: host:card:port:line).
     * Falls back to the raw Zabbix problem name when no dictionary entry exists.
     */
    private String resolveEventDesc(String host, String name) {
        if (!host.startsWith("adlink")) {
            return name;
        }
        Matcher m = TRAP_CARD_PATTERN.matcher(name);
        if (!m.find()) {
            return name;
        }
        String lineKey = host + ":" + m.group(1) + ":" + m.group(2) + ":" + m.group(3);
        String resolved = dictionary.lookupPD(lineKey);
        return lineKey.equals(resolved) ? name : resolved;
    }

    /**
     * Constructs a single {@link Incident} from a Zabbix event timestamp and pre-resolved fields.
     */
    private Incident buildIncident(long epochSec, String location, String host,
                                   String description, Status status, List<String> reviewNames,
                                   String pairKey) {
        LocalDateTime dt = Instant.ofEpochSecond(epochSec)
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        String dateStr = formatUa(dt);
        return new Incident(location, host, epochSec, epochSec,
                dateStr, dateStr, Source.ZABBIX, status, description, reviewNames, pairKey);
    }

    /**
     * Formats a {@link LocalDateTime} as a Ukrainian-locale date-time string
     * ({@code "d mmm yyyy HH:mm:ss"}, e.g. {@code "1 січ 2025 08:00:00"}).
     */
    private static String formatUa(LocalDateTime dt) {
        return String.format("%d %s %d %02d:%02d:%02d",
                dt.getDayOfMonth(), UA_MONTHS[dt.getMonthValue()], dt.getYear(),
                dt.getHour(), dt.getMinute(), dt.getSecond());
    }
}
