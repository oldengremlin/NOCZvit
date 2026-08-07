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
 * Утиліти для локалізації рядків дат і безпечного щодо часової зони перетворення міток часу.
 * Єдине джерело українських назв місяців у проєкті — кожне джерело {@code Incident} (на основі
 * IMAP і Zabbix API) форматує дату для відображення через {@link #convertMonthNumToMnemo} або
 * {@link #formatUa}, тому всі таблиці інцидентів рендеряться однаково незалежно від джерела.
 */
public class DateUtils {

    // Англійське скорочення місяця → українське; перекладає сирий текст заголовка Date: IMAP у convertMonthNumToMnemo.
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b");
    private static final Map<String, String> MONTH_MAP = Map.ofEntries(
            Map.entry("Jan", "січ"), Map.entry("Feb", "лют"), Map.entry("Mar", "бер"), Map.entry("Apr", "квіт"),
            Map.entry("May", "трав"), Map.entry("Jun", "черв"), Map.entry("Jul", "лип"), Map.entry("Aug", "серп"),
            Map.entry("Sep", "вер"), Map.entry("Oct", "жовт"), Map.entry("Nov", "лист"), Map.entry("Dec", "груд")
    );

    // Номер місяця (1-12, відповідає LocalDateTime.getMonthValue()) → українське скорочення;
    // використовується у formatUa. Індекс 0 — невикористаний заповнювач. Ті самі написання, що й у MONTH_MAP вище — тримати синхронізованими.
    private static final String[] UA_MONTHS = {
        "", "січ", "лют", "бер", "квіт", "трав", "черв",
        "лип", "серп", "вер", "жовт", "лист", "груд"
    };

    private DateUtils() {
    }

    /**
     * Замінює англійські скорочення місяців у рядку дати IMAP на українські відповідники,
     * знімає провідний префікс дня тижня й кінцевий зсув часової зони, доповнює нулем
     * односимвольний день.
     *
     * <p>Доповнення має значення: RFC 2822 допускає і {@code "04 Aug"}, і {@code "4 Aug"}, а
     * відправники відрізняються — Zabbix доповнює нулем, реєстратор трапів — ні. Без нормалізації
     * тут формат дня залежав би від того, хто надіслав листа, і не збігався б із
     * {@link #formatUa}, даючи дві різні форми в одній таблиці.
     *
     * @param dt сирий рядок дати (напр. {@code "Mon, 1 Jan 2025 08:00:00 +0200"})
     * @return локалізований рядок дати (напр. {@code "01 січ 2025 08:00:00"})
     */
    public static String convertMonthNumToMnemo(String dt) {
        dt = dt.replaceAll("^\\w{3},\\s+", "")
                .replaceAll("\\s*\\+\\d{4}$", "")
                .replaceFirst("^(\\d) ", "0$1 ");
        Matcher matcher = MONTH_PATTERN.matcher(dt);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, MONTH_MAP.get(matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Форматує {@link LocalDateTime} як рядок дати-часу в українській локалі
     * ({@code "dd mmm yyyy HH:mm:ss"}, напр. {@code "01 січ 2025 08:00:00"}) — той самий формат,
     * що дає {@link #convertMonthNumToMnemo} із сирого заголовка IMAP, для джерел (напр.
     * Zabbix API), які вже мають розібраний {@link LocalDateTime} замість рядка заголовка.
     * @param dt дата-час, що форматується
     * @return дата-час у форматі {@code "dd mmm yyyy HH:mm:ss"} з українською назвою місяця
     */
    public static String formatUa(LocalDateTime dt) {
        return String.format("%02d %s %d %02d:%02d:%02d",
                dt.getDayOfMonth(), UA_MONTHS[dt.getMonthValue()], dt.getYear(),
                dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    /**
     * Форматує {@link Instant} у системній часовій зоні за допомогою {@link #formatUa(LocalDateTime)} —
     * єдиний формат відображення для кожної дати в звіті.
     *
     * @param instant момент часу (мітки часу трапів передаються як instant)
     * @return локалізований рядок дати-часу (напр. {@code "05 серп 2026 13:37:15"})
     */
    public static String formatUa(Instant instant) {
        return formatUa(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    /**
     * Перетворює мітку часу, повідомлену пристроєм у місцевому часі, на {@link Instant},
     * використовуючи для однозначності зсув, чинний на момент відправлення листа-носія.
     *
     * <p>Трапи RAMOS та Emerson повідомляють час за настінним годинником без зони. Під час
     * осіннього перекриття DST одна й та сама година настінного часу трапляється двічі, а
     * {@code atZone()} завжди обирає першу (літню) — це зсуває події з другого проходу на
     * годину в минуле, що може викинути їх з вікна звіту або поставити трап Cleared перед
     * його Active. Заголовок листа {@code Date:} несе справжній зсув, проставлений трохи
     * пізніше, тому саме за ним визначається правильний прохід.
     *
     * @param local             мітка настінного часу, повідомлена пристроєм
     * @param referenceEpochSec епоха в секундах листа-носія ({@code RawMessage.unixDate()})
     * @return розв'язаний instant; для прогалин (перехід на літній час) java.time зсуває вперед як зазвичай
     */
    public static Instant toInstant(LocalDateTime local, long referenceEpochSec) {
        ZoneId zone = ZoneId.systemDefault();
        ZoneOffset preferred = zone.getRules().getOffset(Instant.ofEpochSecond(referenceEpochSec));
        return ZonedDateTime.ofLocal(local, zone, preferred).toInstant();
    }
}
