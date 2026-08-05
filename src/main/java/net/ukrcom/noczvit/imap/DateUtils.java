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
package net.ukrcom.noczvit.imap;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for date string localisation and zone-safe timestamp conversion. The sole source
 * of Ukrainian month names in the project — every {@code Incident} source (IMAP-based and the
 * Zabbix API) formats its display date through {@link #convertMonthNumToMnemo} or
 * {@link #formatUa} so all incident tables render identically regardless of source.
 */
public class DateUtils {

    // English month abbreviation → Ukrainian; translates raw IMAP Date: header text in convertMonthNumToMnemo.
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b");
    private static final Map<String, String> MONTH_MAP = Map.ofEntries(
            Map.entry("Jan", "січ"), Map.entry("Feb", "лют"), Map.entry("Mar", "бер"), Map.entry("Apr", "квіт"),
            Map.entry("May", "трав"), Map.entry("Jun", "черв"), Map.entry("Jul", "лип"), Map.entry("Aug", "серп"),
            Map.entry("Sep", "вер"), Map.entry("Oct", "жовт"), Map.entry("Nov", "лист"), Map.entry("Dec", "груд")
    );

    // Month number (1-12, matching LocalDateTime.getMonthValue()) → Ukrainian abbreviation; used
    // by formatUa. Index 0 is unused padding. Same spellings as MONTH_MAP above — keep in sync.
    private static final String[] UA_MONTHS = {
        "", "січ", "лют", "бер", "квіт", "трав", "черв",
        "лип", "серп", "вер", "жовт", "лист", "груд"
    };

    private DateUtils() {
    }

    /**
     * Replaces English month abbreviations in an IMAP date string with Ukrainian equivalents
     * and strips the leading day-of-week prefix and trailing timezone offset.
     *
     * @param dt raw date string (e.g. {@code "Mon, 01 Jan 2025 08:00:00 +0200"})
     * @return localised date string (e.g. {@code "01 січ 2025 08:00:00"})
     */
    public static String convertMonthNumToMnemo(String dt) {
        dt = dt.replaceAll("^\\w{3},\\s+", "").replaceAll("\\s*\\+\\d{4}$", "");
        Matcher matcher = MONTH_PATTERN.matcher(dt);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, MONTH_MAP.get(matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Formats a {@link LocalDateTime} as a Ukrainian-locale date-time string
     * ({@code "dd mmm yyyy HH:mm:ss"}, e.g. {@code "01 січ 2025 08:00:00"}) — the same format
     * {@link #convertMonthNumToMnemo} produces from a raw IMAP header, for sources (e.g. the
     * Zabbix API) that already have a parsed {@link LocalDateTime} instead of a header string.
     */
    public static String formatUa(LocalDateTime dt) {
        return String.format("%02d %s %d %02d:%02d:%02d",
                dt.getDayOfMonth(), UA_MONTHS[dt.getMonthValue()], dt.getYear(),
                dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    /**
     * Converts a device-reported local timestamp to an {@link Instant}, using the offset that was
     * in effect when the carrying email was sent to disambiguate.
     *
     * <p>RAMOS and Emerson traps report wall-clock time without a zone. During the autumn DST
     * overlap the same wall-clock hour occurs twice, and {@code atZone()} always picks the first
     * (summer) one — putting events from the second pass an hour in the past, which can drop them
     * out of the report window or order a Cleared trap before its Active. The email {@code Date:}
     * header carries a real offset stamped moments later, so it identifies the correct pass.
     *
     * @param local             wall-clock timestamp reported by the device
     * @param referenceEpochSec epoch seconds of the carrying email ({@code RawMessage.unixDate()})
     * @return resolved instant; for gap (spring-forward) times java.time shifts forward as usual
     */
    public static Instant toInstant(LocalDateTime local, long referenceEpochSec) {
        ZoneId zone = ZoneId.systemDefault();
        ZoneOffset preferred = zone.getRules().getOffset(Instant.ofEpochSecond(referenceEpochSec));
        return ZonedDateTime.ofLocal(local, zone, preferred).toInstant();
    }
}
