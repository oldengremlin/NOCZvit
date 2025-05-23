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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Dictionary {

    private final Map<Pattern, String> pdDictionary;
    private final Map<Pattern, String> sdhDictionary;

    public Dictionary(Config config) throws IOException {
        pdDictionary = new HashMap<>();
        sdhDictionary = new HashMap<>();
        loadDictionary(config.getDictionaryPdPath(), "dictionary_pd.txt", pdDictionary);
        loadDictionary(config.getDictionarySdhPath(), "dictionary_sdh.txt", sdhDictionary);
    }

    private void loadDictionary(String filePath, String resourceName, Map<Pattern, String> dictionary) throws IOException {
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

        // Тимчасовий список для сортування
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
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
        } finally {
            input.close();
        }

        // Сортуємо за довжиною regex (довші спочатку)
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());

        // Заповнюємо словник
        for (Map.Entry<String, String> entry : entries) {
            try {
                Pattern pattern = Pattern.compile(entry.getKey());
                dictionary.put(pattern, entry.getValue());
            } catch (Exception e) {
                System.err.println("Invalid regex pattern in dictionary: " + entry.getKey() + ", error: " + e.getMessage());
            }
        }
    }

    public String lookupPD(String key) {
        for (Map.Entry<Pattern, String> entry : pdDictionary.entrySet()) {
            Matcher matcher = entry.getKey().matcher(key);
            if (matcher.find()) {
                //return matcher.replaceAll(entry.getValue());
                return entry.getValue();
            }
        }
        return key;
    }

    public String lookupSDH(String key) {
        for (Map.Entry<Pattern, String> entry : sdhDictionary.entrySet()) {
            Matcher matcher = entry.getKey().matcher(key);
            if (matcher.find()) {
                //return matcher.replaceAll(entry.getValue());
                return entry.getValue();
            }
        }
        return key;
    }
}
