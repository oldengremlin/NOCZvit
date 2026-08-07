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
 * Завантажує та кешує три словники пошуку за regex-ключами: PD (Zabbix/PD-хостнейми →
 * назви локацій), SDH (коди локацій OSM → людинозрозумілі назви) та device-word (префікс
 * Zabbix-хостнейму → українське слово типу пристрою, наприклад {@code "маршрутизаторі "}).
 *
 * <p>Перед компіляцією записи сортуються за довжиною regex (довші — першими), щоб більш
 * специфічні патерни мали перевагу над коротшими, загальнішими. Результати кешуються в
 * {@link ConcurrentHashMap}, тож кожен ключ компілюється й порівнюється лише один раз,
 * незалежно від кількості одночасних викликів.
 */
@Slf4j
public class Dictionary {

    /**
     * Фрагмент regex, що відповідає адресі сухого контакту adlink ({@code "card N, port N, line N"}),
     * спільний для парсера теми IMAP-листа та парсера назви проблеми Zabbix API. Кожен викликач
     * обгортає його власним префіксом, тож нумерація груп захоплення — справа викликача.
     */
    public static final String CARD_PORT_LINE_REGEX =
            "card\\s+(\\d+),\\s*port\\s+(\\d+),\\s*line\\s+(\\d+)";

    // Нормалізація ключа перед пошуком у PD-словнику:
    //   знімаємо префікс r/s/p та ies*/alca- (як у PdIncidentParser.DEVICE_PREFIX_PATTERN)
    //   і лише якщо префікс знятий — знімаємо суфікс -N (порядковий номер вузла).
    // Якщо prefix не збігся — суфікс НЕ знімаємо (щоб не ламати adlink-hoh15-1 тощо).
    private static final Pattern PD_HOST_PREFIX = Pattern.compile("^(?:[rsp]|(?:ies\\d?|alca)-)");
    private static final Pattern PD_HOST_SUFFIX = Pattern.compile("-\\d+$");

    private final Map<Pattern, String> pdDictionary;
    private final Map<Pattern, String> sdhDictionary;
    private final Map<Pattern, String> deviceWordDictionary;
    private final ConcurrentHashMap<String, String> pdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sdhCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> deviceWordCache = new ConcurrentHashMap<>();

    /**
     * Завантажує всі три словники за шляхами з {@code config}, або з вбудованих ресурсів,
     * якщо шляхи не задані (null).
     *
     * @param config джерело шляхів до файлів словників
     * @throws IOException якщо файл/ресурс словника відсутній або недоступний для читання
     */
    public Dictionary(Config config) throws IOException {
        pdDictionary = new LinkedHashMap<>();
        sdhDictionary = new LinkedHashMap<>();
        deviceWordDictionary = new LinkedHashMap<>();
        loadDictionary(config.getDictionaryPdPath(), "dictionary_pd.txt", pdDictionary);
        loadDictionary(config.getDictionarySdhPath(), "dictionary_sdh.txt", sdhDictionary);
        loadDictionary(config.getDictionaryDeviceWordPath(), "dictionary_device_word.txt", deviceWordDictionary);
    }

    /**
     * Читає файл словника у форматі {@code key=value}, компілює кожен ключ як regex і
     * зберігає патерни в {@code dictionary}, відсортовані за довжиною (довші — першими).
     *
     * @param filePath     шлях до зовнішнього файлу, або {@code null}, щоб використати вбудований ресурс
     * @param resourceName ім'я ресурсу в classpath, яке використовується, коли {@code filePath} дорівнює null
     * @param dictionary   цільова мапа для заповнення
     * @throws IOException якщо джерело неможливо відкрити або прочитати
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
     * Відкриває потік для джерела словника: зовнішній файл, якщо задано {@code filePath},
     * або названий ресурс classpath — в іншому разі.
     *
     * @throws IOException якщо файл не знайдено або його неможливо відкрити
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
     * Перекладає PD/Zabbix-хостнейм у людинозрозумілу назву локації.
     *
     * <p>Нормалізація: знімається префікс маршрутизатора/комутатора ({@code r}, {@code s},
     * {@code p}, {@code ies*}, {@code alca-}) і, лише якщо префікс збігся, кінцевий числовий
     * суфікс ({@code -N}). Якщо жоден патерн не збігся — повертається оригінальний ключ.
     * Результати кешуються для повторних викликів.
     *
     * @param key сирий hostname (наприклад {@code r234-1})
     * @return розпізнана назва локації, або незмінений {@code key}, якщо не знайдено
     */
    public String lookupPD(String key) {
        return pdCache.computeIfAbsent(key, k -> {
            String afterPrefix = PD_HOST_PREFIX.matcher(k).replaceFirst("");
            String normalized = afterPrefix.equals(k)
                                ? k
                                : PD_HOST_SUFFIX.matcher(afterPrefix).replaceFirst("");

            // null (а не "") — це сигнальне значення "не знайдено": значення словника ніколи не
            // бувають null, але можуть законно бути порожніми (наприклад «^ramos=» у
            // dictionary_device_word.txt), тож порожній збіг все одно має рахуватись як влучення
            // і пропускати fallback-прохід.
            String byNormalized = firstMatch(pdDictionary, normalized, null);
            if (byNormalized != null) {
                return byNormalized;
            }
            // Fallback: спробуємо оригінальний ключ (коли normalized не збігся, але оригінал збігається)
            if (!normalized.equals(k)) {
                return firstMatch(pdDictionary, k, k);
            }
            return k;
        });
    }

    /**
     * Перекладає код локації SDH/OSM у людинозрозумілу назву.
     * Результати кешуються для повторних викликів.
     *
     * @param key код локації OSM (наприклад {@code KHR__HER})
     * @return розпізнана назва локації, або незмінений {@code key}, якщо не знайдено
     */
    public String lookupSDH(String key) {
        return sdhCache.computeIfAbsent(key, k -> firstMatch(sdhDictionary, k, k));
    }

    /**
     * Перекладає Zabbix-hostname в українське слово типу пристрою для опису інциденту
     * (наприклад {@code "маршрутизаторі"}). На відміну від {@link #lookupPD} і
     * {@link #lookupSDH}, при відсутності збігу повертає {@code ""} (без слова), а не
     * сам ключ. Результати кешуються для повторних викликів.
     *
     * <p>Значення завантажується через {@code .trim()} ({@link #loadDictionary}), тож кінцевий
     * пробіл ніколи не зберігається, навіть якщо він є у файлі словника — його додає сам
     * викликач ({@code ZabbixIncidentConverter}) після непорожнього слова.
     *
     * @param host сирий hostname (наприклад {@code r234-1})
     * @return слово типу пристрою без кінцевого пробілу, або {@code ""}, якщо не знайдено
     */
    public String lookupDeviceWord(String host) {
        return deviceWordCache.computeIfAbsent(host, k -> firstMatch(deviceWordDictionary, k, ""));
    }

    /**
     * Результат пошуку у словнику: розпізнане значення плюс ознака того, чи ключ лишився
     * нерозпізнаним (словник повернув сам ключ) — це відображається у звіті як
     * «потребує коригування назви».
     *
     * @param value       розпізнана назва, або оригінальний ключ, якщо нічого не збіглося
     * @param needsReview {@code true}, якщо нічого не збіглося
     */
    public record Resolution(String value, boolean needsReview) {
    }

    /**
     * Шукає hostname у PD-словнику та повідомляє, чи вдалося його розпізнати.
     *
     * @param key сирий hostname
     * @return розпізнане значення та ознака необхідності перевірки
     */
    public Resolution resolvePD(String key) {
        String value = lookupPD(key);
        return new Resolution(value, value.equals(key));
    }

    /**
     * Шукає код локації OSM у SDH-словнику та повідомляє, чи вдалося його розпізнати.
     *
     * @param key код локації OSM
     * @return розпізнане значення та ознака необхідності перевірки
     */
    public Resolution resolveSDH(String key) {
        String value = lookupSDH(key);
        return new Resolution(value, value.equals(key));
    }

    /**
     * Формує ключ PD-словника для однієї лінії сухого контакту adlink
     * ({@code device:card:port:line}).
     *
     * @param device hostname adlink-пристрою
     * @param card   номер картки
     * @param port   номер порту
     * @param line   номер лінії
     * @return складений ключ словника
     */
    public static String lineKey(String device, String card, String port, String line) {
        return device + ":" + card + ":" + port + ":" + line;
    }

    /**
     * Повертає значення першого запису словника, чий regex збігається з {@code key}, або
     * {@code fallback}, якщо жоден не збігся. Записи попередньо відсортовані за довжиною
     * ключа (довші — першими), тож перевагу має найспецифічніший патерн.
     *
     * <p>Читає лише (фактично незмінну після конструювання) мапу патернів і виділяє новий
     * {@link java.util.regex.Matcher} для кожного запису — {@link Pattern} є потокобезпечним,
     * а {@code Matcher} — ні, тож жоден matcher ніколи не використовується спільно. Свідомо
     * не торкається жодного з кешів: метод виконується всередині {@code computeIfAbsent}, де
     * повторний вхід у ту саму мапу міг би призвести до {@link IllegalStateException} або
     * "зависання" bin'а.
     *
     * @param dictionary скомпільована мапа патерн → значення для сканування
     * @param key        рядок, з яким виконується порівняння
     * @param fallback   значення, що повертається, якщо нічого не збіглося (може бути {@code null})
     * @return знайдене значення, або {@code fallback}
     */
    private static String firstMatch(Map<Pattern, String> dictionary, String key, String fallback) {
        for (Map.Entry<Pattern, String> entry : dictionary.entrySet()) {
            if (entry.getKey().matcher(key).find()) {
                return entry.getValue();
            }
        }
        return fallback;
    }
}
