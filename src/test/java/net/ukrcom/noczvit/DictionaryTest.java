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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Тести {@link Dictionary}: нормалізація ключа PD-пошуку, fallback-поведінка кожного з трьох
 * lookup-методів, сортування за довжиною regex та коректність {@link Dictionary.Resolution}.
 */
class DictionaryTest {

    // ---- lookupPD: нормалізація ключа ----

    @Test
    @DisplayName("lookupPD: префікс 'r' і числовий суфікс знімаються разом")
    void lookupPd_stripsPrefixAndSuffix(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        pd.put("^234$", "Обухів, Малишка 2");
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        // r234-1 -> префікс "r" знятий -> "234-1" -> суфікс "-1" знятий (бо префікс знятий) -> "234"
        assertEquals("Обухів, Малишка 2", dictionary.lookupPD("r234-1"));
    }

    @ParameterizedTest(name = "prefix {0} знімається")
    @CsvSource({
        "r234-1, 234",
        "s234-1, 234",
        "p234-1, 234",
        "ies-234-1, 234",
        "ies3-234-1, 234",
        "alca-234-1, 234"
    })
    @DisplayName("lookupPD: всі варіанти префікса з PD_HOST_PREFIX знімаються разом із суфіксом")
    void lookupPd_prefixVariants(String rawKey, String expectedNormalized, @org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        pd.put("^" + expectedNormalized + "$", "Значення-" + expectedNormalized);
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        assertEquals("Значення-" + expectedNormalized, dictionary.lookupPD(rawKey));
    }

    @Test
    @DisplayName("lookupPD: якщо префікс НЕ збігається, суфікс теж НЕ знімається "
            + "(adlink-hoh15-1 шукається як є)")
    void lookupPd_noPrefixMatch_suffixNotStrippedEither(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        // Якби суфікс знімався незалежно від префікса, знайшовся б цей запис:
        pd.put("^adlink-hoh15$", "НЕПРАВИЛЬНО (суфікс знятий)");
        pd.put("^adlink-hoh15-1$", "Правильно: пошук по оригінальному ключу");
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        assertEquals("Правильно: пошук по оригінальному ключу", dictionary.lookupPD("adlink-hoh15-1"));
    }

    @Test
    @DisplayName("lookupPD: fallback на оригінальний ключ, коли normalized не знайдено, "
            + "але оригінал (з префіксом/суфіксом) збігається")
    void lookupPd_fallbackToOriginalKey(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        // Немає запису на "234" (normalized), але є прямий запис на повний оригінальний ключ.
        pd.put("^r234-1$", "Точний збіг з оригіналом");
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        assertEquals("Точний збіг з оригіналом", dictionary.lookupPD("r234-1"));
    }

    @Test
    @DisplayName("lookupPD: нічого не збіглося ані по normalized, ані по оригіналу -> повертається сам ключ")
    void lookupPd_noMatchAtAll_returnsKey(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of("^nomatch$", "value"));

        assertEquals("r234-1", dictionary.lookupPD("r234-1"));
    }

    @Test
    @DisplayName("lookupPD: без префікса й без суфікса ключ шукається без нормалізації")
    void lookupPd_noPrefixNoSuffix_key_searchedAsIs(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        pd.put("^adlink-hoh15$", "Прахові");
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        assertEquals("Прахові", dictionary.lookupPD("adlink-hoh15"));
    }

    // ---- lookupSDH: без нормалізації, прямий пошук ----

    @Test
    @DisplayName("lookupSDH: прямий пошук без будь-якої нормалізації ключа")
    void lookupSdh_directLookup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(),
                Map.of("^KHR__HER$", "Херсон"), Map.of());

        assertEquals("Херсон", dictionary.lookupSDH("KHR__HER"));
    }

    @Test
    @DisplayName("lookupSDH: r-префікс НЕ знімається (на відміну від lookupPD) — пошук по оригіналу")
    void lookupSdh_noPrefixStripping(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(),
                Map.of("^234$", "НЕ МАЄ ЗНАЙТИСЬ"), Map.of());

        assertEquals("r234-1", dictionary.lookupSDH("r234-1"));
    }

    @Test
    @DisplayName("lookupSDH: немає збігу -> повертається сам ключ")
    void lookupSdh_noMatch_returnsKey(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of("^other$", "x"), Map.of());

        assertEquals("KHR__HER", dictionary.lookupSDH("KHR__HER"));
    }

    // ---- lookupDeviceWord: fallback "" замість ключа ----

    @Test
    @DisplayName("lookupDeviceWord: немає збігу -> повертається порожній рядок, А НЕ ключ")
    void lookupDeviceWord_noMatch_returnsEmptyString(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(),
                Map.of("^other$", "маршрутизаторі "));

        assertEquals("", dictionary.lookupDeviceWord("r234-1"));
    }

    @Test
    @DisplayName("lookupDeviceWord: є збіг -> повертається значення словника "
            + "(loadDictionary() робить .trim() на значенні, тому кінцевий пробіл не зберігається)")
    void lookupDeviceWord_match_returnsValue(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(),
                Map.of("^r234", "маршрутизаторі"));

        assertEquals("маршрутизаторі", dictionary.lookupDeviceWord("r234-1"));
    }

    // ---- Порожнє значення словника — валідний збіг, не сентинел "не знайдено" ----

    @Test
    @DisplayName("lookupPD: порожнє значення в словнику (як ^ramos= у реальному файлі) рахується "
            + "успішним збігом за normalized-ключем, а не 'не знайдено' — fallback-прохід "
            + "по оригінальному ключу НЕ виконується")
    void lookupPd_emptyDictionaryValue_isSuccessfulMatch(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        // "p500-3" -> префікс "p" знятий -> "500-3" -> суфікс "-3" знятий -> normalized="500".
        pd.put("^500$", "");
        // Якби порожній збіг трактувався як "не знайдено", спрацював би fallback на оригінал:
        pd.put("^p500-3$", "НЕ МАЄ ЗНАЙТИСЬ (це був би fallback)");
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        assertEquals("", dictionary.lookupPD("p500-3"));
    }

    @Test
    @DisplayName("resolvePD: порожнє значення словника як збіг -> needsReview=false (не дорівнює ключу)")
    void resolvePd_emptyValue_needsReviewFalse(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of("^500$", ""));

        Dictionary.Resolution resolution = dictionary.resolvePD("p500-3");
        assertEquals("", resolution.value());
        assertFalse(resolution.needsReview());
    }

    @Test
    @DisplayName("lookupDeviceWord: порожнє значення в словнику (^ramos=) рахується успішним збігом "
            + "так само, як і природний fallback '' — обидва невідмінні одне від одного за значенням")
    void lookupDeviceWord_emptyDictionaryValue_isMatch(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(),
                Map.of("^ramos$", ""));

        assertEquals("", dictionary.lookupDeviceWord("ramos"));
    }

    // ---- Resolution: needsReview <=> value.equals(key) ----

    @Test
    @DisplayName("resolvePD: needsReview=true тоді й лише тоді, коли нічого не знайдено (value == key)")
    void resolvePd_needsReviewTrue_whenNoMatch(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of("^nomatch$", "x"));

        Dictionary.Resolution resolution = dictionary.resolvePD("unresolvable-host");
        assertEquals("unresolvable-host", resolution.value());
        assertTrue(resolution.needsReview());
    }

    @Test
    @DisplayName("resolvePD: needsReview=false, коли значення знайдено і не збігається з ключем")
    void resolvePd_needsReviewFalse_whenMatched(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of("^234$", "Обухів"));

        Dictionary.Resolution resolution = dictionary.resolvePD("r234-1");
        assertEquals("Обухів", resolution.value());
        assertFalse(resolution.needsReview());
    }

    @Test
    @DisplayName("resolveSDH: needsReview=true, коли нічого не знайдено")
    void resolveSdh_needsReviewTrue_whenNoMatch(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of("^other$", "x"), Map.of());

        Dictionary.Resolution resolution = dictionary.resolveSDH("KHR__HER");
        assertEquals("KHR__HER", resolution.value());
        assertTrue(resolution.needsReview());
    }

    @Test
    @DisplayName("resolveSDH: needsReview=false, коли значення знайдено")
    void resolveSdh_needsReviewFalse_whenMatched(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of("^KHR__HER$", "Херсон"), Map.of());

        Dictionary.Resolution resolution = dictionary.resolveSDH("KHR__HER");
        assertEquals("Херсон", resolution.value());
        assertFalse(resolution.needsReview());
    }

    @Test
    @DisplayName("resolvePD: збіг, де значення випадково дорівнює ключу, теж дає needsReview=true "
            + "(флаг рахується від значення, не від факту 'було в словнику')")
    void resolvePd_valueEqualsKeyByCoincidence_stillNeedsReview(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        // Значення в словнику навмисно збігається з ключем пошуку.
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of("^same-key$", "same-key"));

        Dictionary.Resolution resolution = dictionary.resolvePD("same-key");
        assertEquals("same-key", resolution.value());
        assertTrue(resolution.needsReview());
    }

    // ---- Сортування за довжиною regex: довші (специфічніші) патерни виграють ----

    @Test
    @DisplayName("Сортування за довжиною regex: довший специфічніший патерн перемагає коротший загальний")
    void lookupPd_longerPatternWinsOverShorter(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        // Короткий загальний патерн, що теж збігається з "234-5" (при пошуку normalized="234-5",
        // бо тут немає числового префікса-суфікса щоб знімати — приберемо суфікс іншим шляхом:
        // скористаємось ключем без префікса й суфікса, аби normalized == key.
        pd.put("^234", "Коротко (загальний)");
        pd.put("^234-5$", "Довго (специфічний)");
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        // Ключ без відомого префікса -> normalized == key == "234-5"; обидва патерни
        // збігаються (find(), не matches()) — має перемогти довший "^234-5$".
        assertEquals("Довго (специфічний)", dictionary.lookupPD("234-5"));
    }

    @Test
    @DisplayName("Сортування за довжиною regex: якщо порядок вставки протилежний до довжини, "
            + "все одно перемагає довший патерн")
    void lookupPd_longerPatternWins_regardlessOfInsertionOrder(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Map<String, String> pd = new LinkedHashMap<>();
        // Вставляємо довгий патерн ПЕРШИМ, аби довести, що перемога — від сортування за
        // довжиною, а не від порядку вставки в LinkedHashMap.
        pd.put("^abcxyz$", "Довгий, вставлений першим");
        pd.put("^abc", "Короткий, вставлений другим");
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, pd);

        assertEquals("Довгий, вставлений першим", dictionary.lookupPD("abcxyz"));
    }

    // ---- lineKey ----

    @Test
    @DisplayName("lineKey: формат device:card:port:line")
    void lineKey_format() {
        assertEquals("dev1:2:3:4", Dictionary.lineKey("dev1", "2", "3", "4"));
    }

    // ---- Кешування: повторний виклик дає той самий результат ----

    @Test
    @DisplayName("lookupPD: повторний виклик з тим самим ключем повертає той самий результат")
    void lookupPd_repeatedCall_consistentResult(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of("^234$", "Обухів"));

        String first = dictionary.lookupPD("r234-1");
        String second = dictionary.lookupPD("r234-1");

        assertEquals("Обухів", first);
        assertEquals(first, second);
    }

    @Test
    @DisplayName("lookupDeviceWord: повторний виклик з тим самим ключем повертає той самий результат")
    void lookupDeviceWord_repeatedCall_consistentResult(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Dictionary dictionary = TestFixtures.dictionary(tempDir, Map.of(), Map.of(),
                Map.of("^r234", "маршрутизаторі"));

        String first = dictionary.lookupDeviceWord("r234-1");
        String second = dictionary.lookupDeviceWord("r234-1");

        assertEquals("маршрутизаторі", first);
        assertEquals(first, second);
    }

    // ---- Порожній словник ----

    @Test
    @DisplayName("Порожній PD-словник: усе fallback на ключ")
    void emptyDictionary_allFallbackToKey(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Dictionary dictionary = TestFixtures.dictionaryPd(tempDir, Map.of());

        assertEquals("r234-1", dictionary.lookupPD("r234-1"));
    }
}
