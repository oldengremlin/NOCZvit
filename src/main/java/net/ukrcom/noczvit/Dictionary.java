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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and caches two regex-keyed lookup dictionaries: PD (Zabbix/PD hostnames → location
 * names) and SDH (OSM location codes → human-readable names).
 *
 * <p>Entries are sorted longest-regex-first before compilation so that more specific patterns
 * win over shorter, more generic ones. Results are cached in a {@link ConcurrentHashMap} so
 * each key is compiled and matched only once across concurrent callers.
 */
@Slf4j
public class Dictionary {

    // Нормалізація ключа перед пошуком у PD-словнику:
    //   знімаємо префікс r/s/p та ies*/alca- (як у PdIncidentParser.DEVICE_PREFIX_PATTERN)
    //   і лише якщо префікс знятий — знімаємо суфікс -N (порядковий номер вузла).
    // Якщо prefix не збігся — суфікс НЕ знімаємо (щоб не ламати adlink-hoh15-1 тощо).
    private static final Pattern PD_HOST_PREFIX = Pattern.compile("^(?:[rsp]|(?:ies\\d?|alca)-)");
    private static final Pattern PD_HOST_SUFFIX = Pattern.compile("-\\d+$");

    private final Map<Pattern, String> pdDictionary;
    private final Map<Pattern, String> sdhDictionary;
    private final ConcurrentHashMap<String, String> pdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sdhCache = new ConcurrentHashMap<>();

    /**
     * Loads both dictionaries using paths from {@code config}, or from bundled resources when
     * the paths are null.
     *
     * @throws IOException if either dictionary file/resource is missing or unreadable
     */
    public Dictionary(Config config) throws IOException {
        pdDictionary = new LinkedHashMap<>();
        sdhDictionary = new LinkedHashMap<>();
        loadDictionary(config.getDictionaryPdPath(), "dictionary_pd.txt", pdDictionary);
        loadDictionary(config.getDictionarySdhPath(), "dictionary_sdh.txt", sdhDictionary);
    }

    /**
     * Reads a {@code key=value} dictionary file, compiles each key as a regex, and stores the
     * patterns sorted longest-first in {@code dictionary}.
     *
     * @param filePath     external file path, or {@code null} to use the bundled resource
     * @param resourceName classpath resource name used when {@code filePath} is null
     * @param dictionary   target map to populate
     * @throws IOException if the source cannot be opened or read
     */
    private void loadDictionary(String filePath, String resourceName, Map<Pattern, String> dictionary) throws IOException {

        // Тимчасовий список для сортування
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        try (InputStream input = openStream(filePath, resourceName); BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String patternStr = parts[0].trim();
                    String value = parts[1].trim();
                    entries.add(Map.entry(patternStr, value));
                }
            }
        }

        // Сортуємо за довжиною regex (довші спочатку)
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());

        // Заповнюємо словник
        for (Map.Entry<String, String> entry : entries) {
            try {
                Pattern pattern = Pattern.compile(entry.getKey());
                dictionary.put(pattern, entry.getValue());
            } catch (Exception e) {
                log.warn("Invalid regex in dictionary: {} — {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Opens a stream for a dictionary source: an external file when {@code filePath} is set,
     * or the named classpath resource otherwise.
     *
     * @throws IOException if the file is not found or cannot be opened
     */
    private InputStream openStream(String filePath, String resourceName) throws IOException {
        InputStream input;
        if (filePath != null) {
            try {
                input = new FileInputStream(filePath);
            } catch (IOException e) {
                throw new IOException("Failed to load dictionary file: " + filePath, e);
            }
        } else {
            input = getClass().getClassLoader().getResourceAsStream(resourceName);
            if (input == null) {
                throw new IOException("Default " + resourceName + " not found in resources");
            }
        }
        return input;
    }

    /**
     * Translates a PD/Zabbix hostname to a human-readable location name.
     *
     * <p>Normalisation: strips the router/switch prefix ({@code r}, {@code s}, {@code p},
     * {@code ies*}, {@code alca-}) and, only when a prefix was matched, the trailing numeric
     * suffix ({@code -N}). Falls back to the original key if no pattern matches.
     * Results are cached for repeated lookups.
     *
     * @param key raw hostname (e.g. {@code r234-1})
     * @return resolved location name, or {@code key} unchanged when not found
     */
    public String lookupPD(String key) {
        return pdCache.computeIfAbsent(key, k -> {
            String afterPrefix = PD_HOST_PREFIX.matcher(k).replaceFirst("");
            String normalized = afterPrefix.equals(k)
                                ? k
                                : PD_HOST_SUFFIX.matcher(afterPrefix).replaceFirst("");

            for (Map.Entry<Pattern, String> entry : pdDictionary.entrySet()) {
                if (entry.getKey().matcher(normalized).find()) {
                    return entry.getValue();
                }
            }
            // Fallback: спробуємо оригінальний ключ (коли normalized не збігся, але оригінал збігається)
            if (!normalized.equals(k)) {
                for (Map.Entry<Pattern, String> entry : pdDictionary.entrySet()) {
                    if (entry.getKey().matcher(k).find()) {
                        return entry.getValue();
                    }
                }
            }
            return k;
        });
    }

    /**
     * Translates an SDH/OSM location code to a human-readable name.
     * Results are cached for repeated lookups.
     *
     * @param key OSM location code (e.g. {@code KHR__HER})
     * @return resolved location name, or {@code key} unchanged when not found
     */
    public String lookupSDH(String key) {
        return sdhCache.computeIfAbsent(key, k -> {
            for (Map.Entry<Pattern, String> entry : sdhDictionary.entrySet()) {
                if (entry.getKey().matcher(k).find()) {
                    return entry.getValue();
                }
            }
            return k;
        });
    }
}
