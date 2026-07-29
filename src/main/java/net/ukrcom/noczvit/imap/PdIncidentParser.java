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

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;

/**
 * Domain: parses Zabbix/PD alert emails into {@link Incident} objects. No I/O —
 * receives a {@link RawMessage} and produces a domain object.
 */
@Slf4j
public class PdIncidentParser {

    private static final Pattern DEVICE_PREFIX_PATTERN = Pattern.compile("^(?:[rsp]|(?:ies\\d?|alca)-)");
    private static final String PATTERN_ORIGINALFROMNAME = ":$";

    private final Dictionary dictionary;

    public PdIncidentParser(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Returns an Incident if the message is a valid PD alert, empty otherwise.
     *
     * @param msg
     * @return
     */
    public Optional<Incident> parse(RawMessage msg) {
        String subject = msg.subject();

        if (isIgnored(subject)) {
            return Optional.empty();
        }

        String[] parts = subject.split("\\s+");
        String from = parts.length > 2 ? parts[2] : "";
        String type = parts.length > 5 ? parts[5] : "";

        if (subject.contains(" Resolved:") && "been".equals(type)) {
            return Optional.empty();
        }

        String originalFromName = from.replaceAll(PATTERN_ORIGINALFROMNAME, "");

        if (from.endsWith(":")) {
            if (!from.matches(".*-\\d+:$")) {
                from = from.replace(":", "-65535:");
            }
            String[] fromParts = from.split(":");
            String fromName = fromParts[0];
            String fromObject = fromName.matches(".*-\\d+$")
                                ? fromName.replaceAll(DEVICE_PREFIX_PATTERN.pattern(), "")
                                : fromName;
            from = fromObject.replace("-65535", "");
            String transformedFrom = dictionary.lookupPD(fromObject);
            boolean needsReview = fromObject.equals(transformedFrom);
            from = transformedFrom;

            Status status = resolveStatus(subject, type);
            String description = buildDescription(status, type, from);
            List<String> reviewNames = needsReview ? List.of(from) : List.of();
            String dateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());

            log.debug("PD parsed: location={}, device={}, ts={}", from, originalFromName, msg.unixDate());
            return Optional.of(new Incident(
                    from, originalFromName,
                    msg.unixDate(), msg.unixDate(),
                    dateLoc, dateLoc,
                    Source.PD, status,
                    description, reviewNames
            ));
        }

        // Subject has no colon-terminated device (e.g. "has been restarted") — device is from plain parts
        Status status = resolveStatus(subject, type);
        String description = buildDescription(status, type, from);
        String dateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());

        log.debug("PD parsed (no device suffix): location={}, ts={}", from, msg.unixDate());
        return Optional.of(new Incident(
                from, originalFromName,
                msg.unixDate(), msg.unixDate(),
                dateLoc, dateLoc,
                Source.PD, status,
                description, List.of()
        ));
    }

    private boolean isIgnored(String subject) {
        return subject.contains("IVR") || subject.contains("TELEVIEV") || subject.contains("Z-SQL")
                || subject.contains("UVPN") || subject.contains("SDH-OSM") || subject.contains("astashov")
                || subject.contains("console") || subject.contains("ramb-\\d+:")
                || subject.matches(".*[dm]: NS\\d?.*")
                || (subject.matches(".*: [ap][^:]+: [ap][^:]+ has.*") && !subject.contains("alca"));
    }

    private Status resolveStatus(String subject, String type) {
        if (subject.contains(" Problem:") && type.contains("been")) {
            return Status.NONE;
        }
        if (subject.contains(" Resolved:")) {
            return Status.END;
        }
        if (subject.contains(" Problem:")) {
            return Status.START;
        }
        return Status.NONE;
    }

    private String buildDescription(Status status, String type, String from) {
        String state = switch (status) {
            case START ->
                "Zabbix зареєстровано початок інциденту, ";
            case END ->
                "Zabbix зареєстровано кінець інциденту, ";
            case NONE ->
                "Zabbix зареєстровано ";
        };
        String eventDesc = switch (type) {
            case "ICMP" ->
                "зникнення зв'язку з обладнанням на ";
            case "Unavailable", "by" ->
                "зникнення підключення ";
            case "been" ->
                "перезавантаження обладнання ";
            default ->
                type + " ";
        };
        return (state + eventDesc + from).replaceAll("\\s+", " ");
    }
}
