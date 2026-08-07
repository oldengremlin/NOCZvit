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
import net.ukrcom.noczvit.model.IncidentDescriptions;

/**
 * Домен: парсить листи-алерти Zabbix/PD в об'єкти {@link Incident}. Без I/O —
 * отримує {@link RawMessage} і повертає доменний об'єкт.
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
     * Повертає Incident, якщо повідомлення є валідним алертом PD, інакше empty.
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
            // ВІДОМА ОСОБЛИВІСТЬ (свідомо не виправлено): якщо fromObject після цього зняття
            // сам починається з r/s/p чи ies*/alca- (напр. "ssks-2" → "sks-2"), Dictionary.resolvePD
            // знову зніме префікс і суфікс — подвійне зняття. У наявному словнику це рятує
            // fallback-прохід lookupPD по оригінальному ключу (сам збігається за коротшим
            // префіксом), тож на реальних даних хибного результату не спостерігалось. Ризик
            // теоретичний: спрацював би, лише якби подвійно-зрізаний залишок випадково збігався
            // з ІНШИМ записом словника (тоді fallback узагалі не викликається).
            String fromObject = fromName.matches(".*-\\d+$")
                                ? fromName.replaceAll(DEVICE_PREFIX_PATTERN.pattern(), "")
                                : fromName;
            Dictionary.Resolution resolved = dictionary.resolvePD(fromObject);
            boolean needsReview = resolved.needsReview();
            from = resolved.value();

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
                    description, reviewNames,
                    msg.inReplyTo()
            ));
        }

        // У темі немає пристрою, що закінчується двокрапкою (напр. "has been restarted") — пристрій береться з plain parts
        Status status = resolveStatus(subject, type);
        String description = buildDescription(status, type, from);
        String dateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());

        log.debug("PD parsed (no device suffix): location={}, ts={}", from, msg.unixDate());
        return Optional.of(new Incident(
                from, originalFromName,
                msg.unixDate(), msg.unixDate(),
                dateLoc, dateLoc,
                Source.PD, status,
                description, List.of(),
                msg.inReplyTo()
        ));
    }

    /**
     * Повертає {@code true} для тем, які слід мовчки пропускати (IVR, SDH-OSM,
     * console, UVPN, паровані патерни host-port тощо).
     */
    private boolean isIgnored(String subject) {
        return subject.contains("IVR") || subject.contains("TELEVIEV") || subject.contains("Z-SQL")
                || subject.contains("UVPN") || subject.contains("SDH-OSM") || subject.contains("astashov")
                || subject.contains("console")
                || subject.matches(".*[dm]: NS\\d?.*")
                || (subject.matches(".*: [ap][^:]+: [ap][^:]+ has.*") && !subject.contains("alca"));
    }

    /**
     * Визначає статус інциденту за ключовим словом теми та токеном типу події.
     *
     * <p>Розширює {@link IncidentDescriptions#resolveStatus} одним правилом, властивим лише PD:
     * перезавантаження ({@code type="been"}) надходить під темою {@code "Problem:"}, але є
     * інформаційною подією, а не початком інциденту.
     */
    private Status resolveStatus(String subject, String type) {
        if (subject.contains(" Problem:") && type.contains("been")) {
            return Status.NONE;
        }
        return IncidentDescriptions.resolveStatus(subject);
    }

    /**
     * Складає текстовий опис інциденту з префікса статусу, токена типу події
     * та резолвленої назви локації.
     */
    private String buildDescription(Status status, String type, String from) {
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
        return IncidentDescriptions.describe(
                IncidentDescriptions.SOURCE_ZABBIX, status, eventDesc + from);
    }
}
