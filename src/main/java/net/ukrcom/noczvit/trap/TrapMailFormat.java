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
package net.ukrcom.noczvit.trap;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Константи формату повідомлення, спільні для кожного листа з SNMP-трапом, незалежно від того,
 * який пристрій його надіслав.
 *
 * <p>Усі листи з трапами формуються одним і тим самим приймачем трапів, тому починаються з
 * однакового рядка заголовка — {@code "At <timestamp>, from <ip>, after uptime <u>, registered
 * trap:"} — хоча вміст, що йде далі, відрізняється залежно від вендора (Emerson видає вільний
 * текст, RAMOS — три поля в лапках). Тут живе лише спільний заголовок і формат його timestamp;
 * граматику власного тіла кожен парсер тримає у себе.
 *
 * <p><b>Потокобезпека:</b> лише константи. {@link DateTimeFormatter} незмінний і безпечний для
 * спільного використання гілками парсингу Emerson і RAMOS, які виконуються паралельно на
 * віртуальних потоках.
 */
final class TrapMailFormat {

    /**
     * Префікс регулярного виразу, що захоплює заголовок трапу: група 1 = timestamp,
     * група 2 = IP джерела. Виклики додають власну граматику тіла й продовжують нумерацію
     * з групи 3.
     */
    static final String HEADER_PREFIX =
            "At\\s+(\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}),\\s+from\\s+([\\d.]+),";

    /** Формат timestamp, що використовується в рядку заголовка трапу. */
    static final DateTimeFormatter HEADER_TIMESTAMP =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH);

    private TrapMailFormat() {
    }
}
