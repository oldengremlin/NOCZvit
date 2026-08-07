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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.imap.RawMessage;

/**
 * Розбирає листи з трапами датчиків довкілля RAMOS в об'єкти {@link RamosTrapEvent}.
 *
 * <p>Очікувана тема листа: містить {@code "Got trap from ramos"} (без урахування регістру).
 *
 * <p>Очікуваний формат тіла на кожен трап:
 * <pre>
 * At DD-MM-YYYY HH:MM:SS, from IP, after uptime D:HH:MM:SS.ms, registered trap:
 * \t"STATE" / "SENSOR_NAME" / "SENSOR_TYPE"
 * </pre>
 *
 * <p>Назви датчиків можуть надходити як hex-дампи байтів, згенеровані Perl (відома вада
 * Perl-відправника RAMOS), і можуть займати кілька рядків. Приклад:
 * {@code "52 6F 6F 6D 34 20 D0 90 D0 9D D0 A2 D0 98\n D0 9F 20 53 30 36"}
 * декодується в UTF-8 як {@code "Room4 АНТИПОТОП S06"}.
 *
 * <p>Повертаються лише події, чий стан — один з Critical, High Critical, Low Critical,
 * High Warning, Low Warning, Warning або Sensor Error
 * (див. {@link RamosTrapEvent#REPORTABLE_STATES}).
 */
@Slf4j
public class RamosTrapParser {

    // Спільний заголовок (група 1 = timestamp, група 2 = IP джерела) + власне тіло RAMOS: три
    // поля в лапках — стан, назва датчика, тип датчика (групи 3-5).
    // [^\n]* поглинає частину з uptime, не переходячи межу рядка.
    private static final Pattern TRAP_RE = Pattern.compile(
            TrapMailFormat.HEADER_PREFIX
            + "[^\\n]*registered trap:\\s*\\r?\\n"
            + "[ \\t]+\"([^\"]+)\"\\s*/\\s+\"([^\"]+)\"\\s*/\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    // Назва датчика вважається hex-кодованою, якщо цілком складається з пар hex-байтів,
    // розділених пробілами. Потрібно щонайменше 4 групи: короткі назви на кшталт "AC"
    // (кондиціонер) чи "DC DC" теж є валідним hex і інакше декодувалися б у символи заміни.
    private static final Pattern HEX_SENSOR_RE = Pattern.compile(
            "^(?:[0-9A-Fa-f]{2}\\s+){3,}[0-9A-Fa-f]{2}\\s*$");

    private static final Pattern ROOM_RE = Pattern.compile("(?i)room\\s*(\\d)");

    private RamosTrapParser() {
    }

    /**
     * Розбирає всі листи з трапами RAMOS зі списку сирих повідомлень.
     *
     * @param messages сирі IMAP-повідомлення (можуть містити й не-RAMOS листи; вони пропускаються)
     * @return список розібраних подій у порядку надходження; ніколи не null
     */
    public static List<RamosTrapEvent> parse(List<RawMessage> messages) {
        List<RamosTrapEvent> result = new ArrayList<>();
        for (RawMessage msg : messages) {
            if (!msg.subject().toLowerCase(Locale.ROOT).contains("got trap from ramos")) {
                continue;
            }
            parseBody(msg.body(), msg.unixDate(), result);
        }
        return result;
    }

    /**
     * Знаходить у тілі листа всі трапи, що відповідають {@link #TRAP_RE}, і для кожного,
     * чий стан входить до {@link RamosTrapEvent#REPORTABLE_STATES}, додає розібрану подію
     * до {@code result}.
     */
    private static void parseBody(String body, long messageEpochSec, List<RamosTrapEvent> result) {
        if (body == null || body.isBlank()) {
            return;
        }
        Matcher m = TRAP_RE.matcher(body);
        while (m.find()) {
            String timestampStr = m.group(1);
            String ip           = m.group(2).strip();
            String state        = m.group(3).strip();
            String sensorNameRaw = m.group(4);
            String sensorType   = m.group(5).strip();

            if (!RamosTrapEvent.REPORTABLE_STATES.contains(state)) {
                continue;
            }

            // Нормалізуємо багаторядковий hex: внутрішні переноси рядків + провідні пробіли
            // згортаємо в один пробіл
            String sensorNameNorm = sensorNameRaw.replaceAll("[\\r\\n]+[ \\t]*", " ").strip();
            String decodedName = decodeSensorName(sensorNameNorm);
            String room = extractRoom(decodedName);
            String sensorName = expandAbbreviations(decodedName);

            Instant timestamp;
            try {
                timestamp = DateUtils.toInstant(
                        LocalDateTime.parse(timestampStr.trim(), TrapMailFormat.HEADER_TIMESTAMP), messageEpochSec);
            } catch (Exception e) {
                log.debug("RamosTrapParser: cannot parse timestamp «{}»", timestampStr);
                continue;
            }

            log.debug("RamosTrapParser: {} {} | {} | {} | {}",
                    timestamp, ip, state, sensorName, sensorType);
            result.add(new RamosTrapEvent(timestamp, ip, state, sensorName, sensorType, room));
        }
    }

    /**
     * Декодує назву датчика, що є hex-дампом байтів від Perl, назад у UTF-8.
     * Повертає початковий рядок без змін, якщо він не відповідає hex-шаблону.
     */
    private static String decodeSensorName(String raw) {
        if (!HEX_SENSOR_RE.matcher(raw).matches()) {
            return raw;
        }
        try {
            byte[] bytes = HexFormat.of().parseHex(raw.replaceAll("\\s+", ""));
            // Строге декодування: new String(bytes, UTF_8) мовчки видало б U+FFFD для
            // обрізаного дампу, тож назва, що виглядає як hex, але не є валідним UTF-8,
            // має лишитись як є.
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException | IllegalArgumentException e) {
            log.debug("RamosTrapParser: hex decode failed for «{}»: {}", raw, e.getMessage());
            return raw;
        }
    }

    /** Розгортає скорочення в назві датчика (AVR, room N) у повні україномовні формулювання. */
    private static String expandAbbreviations(String name) {
        name = name.replaceAll("(?i)\\bAVR\\b", "автоматичний ввід резерву");
        name = name.replaceAll("(?i)\\broom\\s*(\\d+)", "зал $1");
        return name;
    }

    /** Витягує номер залу з назви датчика; якщо не знайдено — повертає "Інші". */
    private static String extractRoom(String sensorName) {
        Matcher m = ROOM_RE.matcher(sensorName);
        return m.find() ? "Room" + m.group(1) : "Інші";
    }
}
