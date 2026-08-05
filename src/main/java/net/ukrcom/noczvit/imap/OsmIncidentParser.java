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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;
import net.ukrcom.noczvit.model.IncidentDescriptions;

/**
 * Domain: parses OSM/SDH alert emails into {@link Incident} objects. No I/O —
 * receives a {@link RawMessage} and produces a domain object.
 */
@Slf4j
public class OsmIncidentParser {

    private static final DateTimeFormatter TRAP_DATE_INPUT_FORMATTER
            = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter TRAP_DATE_OUTPUT_FORMATTER
            = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private static final Pattern PATTERN_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    private final Dictionary dictionary;

    public OsmIncidentParser(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Returns an Incident if the message is a valid OSM alert, empty otherwise.
     *
     * @param msg
     * @return
     */
    public Optional<Incident> parse(RawMessage msg) {
        String subject = msg.subject();
        log.debug("Processing SDH message: subject={}, ts={}", subject, msg.unixDate());

        String[] parts = subject.split("\\s+");
        String geo = parts.length > 3 ? parts[3] : "";
        String type = parts.length > 5 ? parts[5] : "";

        String from = geo;
        String to = "";
        if ("STM".equals(type)) {
            String[] geoParts = geo.split("__");
            from = geoParts[0];
            to = geoParts.length > 1 ? geoParts[1] : "";
        }

        Dictionary.Resolution fromRes = dictionary.resolveSDH(from);
        from = fromRes.value();
        boolean needsReviewFrom = fromRes.needsReview();

        // An absent "to" (non-STM subjects) resolves to itself and would look unresolved —
        // an empty code is not a missing dictionary entry, so it never goes to review.
        String originalTo = to;
        Dictionary.Resolution toRes = dictionary.resolveSDH(to);
        to = toRes.value();
        boolean needsReviewTo = !originalTo.isEmpty() && toRes.needsReview();

        String geoMsg = "STM".equals(type)
                        ? (to.isEmpty() ? "на " + from : "з " + from + " на " + to)
                        : from;

        Status status = IncidentDescriptions.resolveStatus(subject);

        String eventDesc;
        if ("Power".equals(type)) {
            eventDesc = subject.contains("Air Condition")
                        ? "зникнення живлення на " + geoMsg + " до кондиціонерів"
                        : "зникнення живлення на виносі " + geoMsg;
        } else {
            eventDesc = "втрата зв'язності " + geoMsg;
        }
        String description = IncidentDescriptions.describe(
                IncidentDescriptions.SOURCE_OSM, status, eventDesc, "інцидент, ");

        // Extract precise event time from Trap value in body
        long eventTs = msg.unixDate();
        String eventDateStr = msg.dateStr();
        String[] lines = msg.body().replace("\r", "").split("\n");
        for (String line : lines) {
            if (line.startsWith("Trap value:")) {
                log.debug("Trap value line: {}", line);
                Matcher matcher = PATTERN_DATE.matcher(line);
                if (matcher.find()) {
                    try {
                        LocalDateTime ldt = LocalDateTime.parse(matcher.group(), TRAP_DATE_INPUT_FORMATTER);
                        eventTs = DateUtils.toInstant(ldt, msg.unixDate()).getEpochSecond();
                        eventDateStr = ldt.atZone(ZoneId.systemDefault()).format(TRAP_DATE_OUTPUT_FORMATTER);
                        log.debug("Found Trap value date: {}, updated ts={}", matcher.group(), eventTs);
                    } catch (DateTimeParseException e) {
                        log.warn("Failed to parse Trap value date: {} — {}", matcher.group(), e.getMessage());
                    }
                }
                break;
            }
        }

        // An event cannot have happened after the alert that reports it, yet OSM regularly sends
        // a Trap value a couple of minutes ahead of its own mail — clock skew between the OSM
        // host and the mail server, not a real event time. Clamp those to the alert time.
        if (eventTs > msg.unixDate()) {
            log.debug("Trap value {} is after the alert ({}), clamping to the alert time",
                    eventTs, msg.unixDate());
            eventTs = msg.unixDate();
            eventDateStr = msg.dateStr();
        }

        // Only a Trap value that predates the alert says something the Початок column does not —
        // the event sat unreported for a while. A value equal to it (including everything just
        // clamped above) adds nothing, so the note is omitted.
        if (eventTs < msg.unixDate()) {
            description += ", який відбувся " + DateUtils.convertMonthNumToMnemo(eventDateStr);
        }

        List<String> reviewNames = new ArrayList<>();
        if (needsReviewFrom) {
            reviewNames.add(from);
        }
        if (needsReviewTo) {
            reviewNames.add(to);
        }

        String messageDateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());
        String eventDateLoc = DateUtils.convertMonthNumToMnemo(eventDateStr);

        log.debug("SDH stored: from={}, to={}, ts={}", from, to, msg.unixDate());
        return Optional.of(new Incident(
                from, "",
                msg.unixDate(), eventTs,
                messageDateLoc, eventDateLoc,
                Source.OSM, status,
                description, List.copyOf(reviewNames),
                msg.inReplyTo()
        ));
    }
}
