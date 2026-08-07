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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;
import net.ukrcom.noczvit.model.Incident.Source;
import net.ukrcom.noczvit.model.Incident.Status;
import net.ukrcom.noczvit.model.IncidentDescriptions;

/**
 * Домен: парсить листи-алерти Zabbix ospfNbrStateChange в об'єкти {@link Incident}.
 * Формат теми: "[±] Problem/Resolved: <host>: <router> <channel>
 * ospfNbrStateChange"
 */
@Slf4j
public class OspfIncidentParser {

    private final Dictionary dictionary;

    public OspfIncidentParser(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Повертає Incident, якщо повідомлення є валідним алертом зміни стану
     * OSPF-сусіда.
     * @param msg сире email-повідомлення
     * @return розпізнаний інцидент, або {@link Optional#empty()}, якщо тема не відповідає формату
     */
    public Optional<Incident> parse(RawMessage msg) {
        String subject = msg.subject();
        String[] parts = subject.split("\\s+");
        // parts[2] = "host:", parts[3] = router (маршрутизатор), parts[4] = channel (канал)
        String router = parts.length > 3 ? parts[3] : "";
        String channel = parts.length > 4 ? parts[4] : "";

        String originalRouter = router;
        Dictionary.Resolution routerRes = dictionary.resolvePD(router);
        router = routerRes.value();

        String originalChannel = channel;
        Dictionary.Resolution channelRes = dictionary.resolvePD(channel);
        channel = channelRes.value();

        Status status = IncidentDescriptions.resolveStatus(subject);
        String description = IncidentDescriptions.describe(IncidentDescriptions.SOURCE_ZABBIX, status,
                "падіння каналу на " + router + " по каналу " + channel);

        List<String> reviewNames = new ArrayList<>();
        if (routerRes.needsReview()) {
            reviewNames.add(originalRouter);
        }
        if (channelRes.needsReview()) {
            reviewNames.add(originalChannel);
        }

        String dateLoc = DateUtils.convertMonthNumToMnemo(msg.dateStr());

        log.debug("OSPF parsed: router={}, channel={}, ts={}", router, channel, msg.unixDate());
        return Optional.of(new Incident(
                router, originalRouter,
                msg.unixDate(), msg.unixDate(),
                dateLoc, dateLoc,
                Source.PD, status,
                description, List.copyOf(reviewNames),
                msg.inReplyTo()
        ));
    }
}
