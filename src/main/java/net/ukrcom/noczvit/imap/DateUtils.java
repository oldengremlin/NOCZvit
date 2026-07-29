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

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package-private utilities for date string localisation.
 */
class DateUtils {

    private static final Pattern MONTH_PATTERN = Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b");
    private static final Map<String, String> MONTH_MAP = Map.ofEntries(
            Map.entry("Jan", "січ"), Map.entry("Feb", "лют"), Map.entry("Mar", "бер"), Map.entry("Apr", "квіт"),
            Map.entry("May", "трав"), Map.entry("Jun", "черв"), Map.entry("Jul", "лип"), Map.entry("Aug", "серп"),
            Map.entry("Sep", "вер"), Map.entry("Oct", "жовт"), Map.entry("Nov", "лист"), Map.entry("Dec", "груд")
    );

    private DateUtils() {
    }

    static String convertMonthNumToMnemo(String dt) {
        dt = dt.replaceAll("^\\w{3},\\s+", "").replaceAll("\\+\\d{4}$", "");
        Matcher matcher = MONTH_PATTERN.matcher(dt);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, MONTH_MAP.get(matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
