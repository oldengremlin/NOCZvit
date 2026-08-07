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
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DateUtilsTest {

    // toInstant() читає ZoneId.systemDefault() на кожен виклик, тож фіксуємо його
    // на зону з реальним DST (Europe/Kyiv), щоб детерміновано перевірити розв'язання
    // осіннього перекриття та весняного розриву.
    private static TimeZone originalDefault;

    @BeforeAll
    static void fixDefaultZone() {
        originalDefault = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Kyiv"));
    }

    @AfterAll
    static void restoreDefaultZone() {
        TimeZone.setDefault(originalDefault);
    }

    // --- convertMonthNumToMnemo ---

    @Test
    void convertMonthNumToMnemo_stripsDayOfWeekAndOffset_padsSingleDigitDay() {
        assertEquals("01 січ 2025 08:00:00",
                DateUtils.convertMonthNumToMnemo("Mon, 1 Jan 2025 08:00:00 +0200"));
    }

    @Test
    void convertMonthNumToMnemo_alreadyPaddedDay_isLeftAsIs() {
        assertEquals("04 серп 2025 08:00:00",
                DateUtils.convertMonthNumToMnemo("Thu, 04 Aug 2025 08:00:00 +0300"));
    }

    @Test
    void convertMonthNumToMnemo_noDayOfWeekPrefix_stillPadsAndStripsOffset() {
        assertEquals("01 січ 2025 08:00:00",
                DateUtils.convertMonthNumToMnemo("1 Jan 2025 08:00:00 +0200"));
    }

    @Test
    void convertMonthNumToMnemo_noOffsetAtAll() {
        assertEquals("09 вер 2025 12:00:00",
                DateUtils.convertMonthNumToMnemo("Tue, 9 Sep 2025 12:00:00"));
    }

    /**
     * ФІКСАЦІЯ БАГА: коли заголовок несе коментар зони на кшталт {@code (EEST)} після офсету
     * (звичайний формат багатьох MTA), офсет {@code +\d{4}} більше не в кінці рядка — regex
     * {@code \s*\+\d{4}$} на нього не спрацьовує, і офсет разом з коментарем лишаються
     * у вихідному рядку. Це суперечить javadoc методу ("strips ... trailing timezone offset").
     * Тест документує ПОТОЧНУ (не виправлену) поведінку.
     */
    @Test
    void convertMonthNumToMnemo_zoneCommentSuffix_offsetIsNotStripped_documentedBug() {
        assertEquals("04 серп 2025 08:00:00 +0300 (EEST)",
                DateUtils.convertMonthNumToMnemo("Mon, 4 Aug 2025 08:00:00 +0300 (EEST)"));
    }

    @ParameterizedTest
    @CsvSource({
        "Jan, січ",
        "Feb, лют",
        "Mar, бер",
        "Apr, квіт",
        "May, трав",
        "Jun, черв",
        "Jul, лип",
        "Aug, серп",
        "Sep, вер",
        "Oct, жовт",
        "Nov, лист",
        "Dec, груд"
    })
    void convertMonthNumToMnemo_allMonthAbbreviations(String enMonth, String uaMonth) {
        String header = "Wed, 15 " + enMonth + " 2025 10:00:00 +0200";
        assertEquals("15 " + uaMonth + " 2025 10:00:00", DateUtils.convertMonthNumToMnemo(header));
    }

    // --- formatUa(LocalDateTime) ---

    @Test
    void formatUa_localDateTime_padsDayAndTime() {
        assertEquals("07 серп 2026 03:05:09",
                DateUtils.formatUa(LocalDateTime.of(2026, 8, 7, 3, 5, 9)));
    }

    @Test
    void formatUa_localDateTime_januaryFirst() {
        assertEquals("01 січ 2025 08:00:00",
                DateUtils.formatUa(LocalDateTime.of(2025, 1, 1, 8, 0, 0)));
    }

    // --- formatUa(Instant) ---

    @Test
    void formatUa_instant_delegatesThroughSystemDefaultZone() {
        // Обхід instant -> local -> instant тою самою зоною, щоб не «вигадувати» значення,
        // залежне від того, яка зона за замовчуванням у CI.
        LocalDateTime local = LocalDateTime.of(2025, 1, 1, 8, 0, 0);
        Instant instant = local.atZone(ZoneId.systemDefault()).toInstant();
        assertEquals("01 січ 2025 08:00:00", DateUtils.formatUa(instant));
    }

    // --- toInstant: DST-неоднозначність (Europe/Kyiv) ---

    @Test
    void toInstant_unambiguousSummerTime_ignoresReference() {
        LocalDateTime local = LocalDateTime.of(2026, 8, 7, 13, 37, 15);
        Instant expected = local.atZone(ZoneId.of("Europe/Kyiv")).toInstant();
        assertEquals(expected, DateUtils.toInstant(local, 0L));
    }

    @Test
    void toInstant_autumnOverlap_earlyReference_resolvesToFirstPass_EEST() {
        // 2025-10-26 03:30 трапляється двічі. Лист надійшов до переходу (offset +03:00 EEST).
        LocalDateTime local = LocalDateTime.of(2025, 10, 26, 3, 30, 0);
        long referenceEpochSec = Instant.parse("2025-10-26T00:30:00Z").getEpochSecond();
        Instant expected = Instant.parse("2025-10-26T00:30:00Z");
        assertEquals(expected, DateUtils.toInstant(local, referenceEpochSec));
    }

    @Test
    void toInstant_autumnOverlap_lateReference_resolvesToSecondPass_EET() {
        // Той самий локальний час, але лист надійшов після переходу (offset +02:00 EET).
        LocalDateTime local = LocalDateTime.of(2025, 10, 26, 3, 30, 0);
        long referenceEpochSec = Instant.parse("2025-10-26T01:30:00Z").getEpochSecond();
        Instant expected = Instant.parse("2025-10-26T01:30:00Z");
        assertEquals(expected, DateUtils.toInstant(local, referenceEpochSec));
    }

    @Test
    void toInstant_springGap_shiftsForwardByGapLength() {
        // 2025-03-30 03:30 у Europe/Kyiv не існує (02:59:59 EET -> 04:00:00 EEST).
        // java.time зсуває такий локальний час вперед на довжину розриву незалежно від
        // referenceEpochSec, як і описано в javadoc методу.
        LocalDateTime local = LocalDateTime.of(2025, 3, 30, 3, 30, 0);
        Instant expected = Instant.parse("2025-03-30T01:30:00Z");
        assertEquals(expected, DateUtils.toInstant(local, 0L));
        assertEquals(expected, DateUtils.toInstant(local, Instant.parse("2025-03-30T12:00:00Z").getEpochSecond()));
    }
}
