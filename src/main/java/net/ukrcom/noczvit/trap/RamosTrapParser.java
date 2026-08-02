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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.imap.RawMessage;

/**
 * Parses RAMOS environmental-sensor trap emails into {@link RamosTrapEvent} objects.
 *
 * <p>Expected email subject: contains {@code "Got trap from ramos"} (case-insensitive).
 *
 * <p>Expected body format per trap:
 * <pre>
 * At DD-MM-YYYY HH:MM:SS, from IP, after uptime D:HH:MM:SS.ms, registered trap:
 * \t"STATE" / "SENSOR_NAME" / "SENSOR_TYPE"
 * </pre>
 *
 * <p>Sensor names may appear as Perl-generated hex byte dumps (a known bug in the RAMOS Perl
 * sender) and can span multiple lines. Example:
 * {@code "52 6F 6F 6D 34 20 D0 90 D0 9D D0 A2 D0 98\n D0 9F 20 53 30 36"}
 * is decoded to UTF-8 as {@code "Room4 АНТИПОТОП S06"}.
 *
 * <p>Only events whose state is one of Critical, High Critical, Low Critical,
 * High Warning, Low Warning, or Warning are returned.
 */
@Slf4j
public class RamosTrapParser {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH);

    // Matches the header line; group 1 = timestamp, group 2 = source IP.
    // [^\n]* swallows the uptime portion without crossing the line boundary.
    private static final Pattern TRAP_RE = Pattern.compile(
            "At\\s+(\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}),\\s+from\\s+([\\d.]+),"
            + "[^\\n]*registered trap:\\s*\\r?\\n"
            + "[ \\t]+\"([^\"]+)\"\\s*/\\s+\"([^\"]+)\"\\s*/\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    // Sensor name is hex-encoded when it consists entirely of space-separated hex byte pairs.
    // At least 4 groups are required: shorter names like "AC" (air conditioner) or "DC DC"
    // are valid hex too and would otherwise be decoded into replacement characters.
    private static final Pattern HEX_SENSOR_RE = Pattern.compile(
            "^(?:[0-9A-Fa-f]{2}\\s+){3,}[0-9A-Fa-f]{2}\\s*$");

    private static final Pattern ROOM_RE = Pattern.compile("(?i)room\\s*(\\d)");

    private static final Set<String> REPORTABLE_STATES = Set.of(
            "Critical", "High Critical", "Low Critical",
            "High Warning", "Low Warning", "Warning",
            "Sensor Error");

    private RamosTrapParser() {
    }

    /**
     * Parses all RAMOS trap messages from the given raw message list.
     *
     * @param messages raw IMAP messages (may contain non-RAMOS messages; they are skipped)
     * @return list of parsed events in arrival order; never null
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

            if (!REPORTABLE_STATES.contains(state)) {
                continue;
            }

            // Normalise multiline hex: collapse internal newlines + leading whitespace into a space
            String sensorNameNorm = sensorNameRaw.replaceAll("[\\r\\n]+[ \\t]*", " ").strip();
            String decodedName = decodeSensorName(sensorNameNorm);
            String room = extractRoom(decodedName);
            String sensorName = expandAbbreviations(decodedName);

            Instant timestamp;
            try {
                timestamp = DateUtils.toInstant(
                        LocalDateTime.parse(timestampStr.trim(), DATE_FORMATTER), messageEpochSec);
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
     * Decodes a sensor name that is a Perl hex byte dump back to UTF-8.
     * Returns the original string unchanged when it does not match the hex pattern.
     */
    private static String decodeSensorName(String raw) {
        if (!HEX_SENSOR_RE.matcher(raw).matches()) {
            return raw;
        }
        try {
            byte[] bytes = HexFormat.of().parseHex(raw.replaceAll("\\s+", ""));
            // Strict decode: new String(bytes, UTF_8) silently yields U+FFFD for a truncated
            // dump, so a name that is hex-shaped but not valid UTF-8 must stay as-is.
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

    private static String expandAbbreviations(String name) {
        name = name.replaceAll("(?i)\\bAVR\\b", "автоматичний ввід резерву");
        name = name.replaceAll("(?i)\\broom\\s*(\\d+)", "зал $1");
        return name;
    }

    private static String extractRoom(String sensorName) {
        Matcher m = ROOM_RE.matcher(sensorName);
        return m.find() ? "Room" + m.group(1) : "Інші";
    }
}
