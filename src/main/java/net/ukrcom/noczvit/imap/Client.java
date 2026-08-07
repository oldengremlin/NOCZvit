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

import jakarta.mail.MessagingException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.Dictionary;
import net.ukrcom.noczvit.model.Incident;

/**
 * Оркеструє читання IMAP та розбір інцидентів. Делегує ввід/вивід
 * {@link ImapReader}, бізнес-логіку — {@link PdIncidentParser} та
 * {@link OsmIncidentParser}.
 */
@Slf4j
public class Client {

    // Zabbix надсилає кожен adlink-алерт двічі протягом кількох секунд; згортаємо повтори
    // з однаковою темою, що надходять у межах цього вікна.
    private static final long ADLINK_DEDUP_WINDOW_SEC = 60;

    private final Config config;
    private final ImapReader reader;
    private final PdIncidentParser pdParser;
    private final OsmIncidentParser osmParser;
    private final OspfIncidentParser ospfParser;
    private final AdlinkIncidentParser adlinkParser;

    /**
     * Створює клієнт, використовуючи вже завантажений словник.
     *
     * <p>Словник передається ззовні, а не створюється тут, щоб увесь процес користувався
     * одним екземпляром: побудова другого перечитувала б обидва файли, перекомпільовувала б
     * кожен патерн і давала парсерам кеш пошуку, окремий від того, яким користується
     * {@code ZabbixIncidentConverter}.
     * @param config     конфігурація з'єднання IMAP
     * @param dictionary вже завантажений спільний словник
     */
    public Client(Config config, Dictionary dictionary) {
        this.config = config;
        this.reader = new ImapReader(config);
        this.pdParser = new PdIncidentParser(dictionary);
        this.osmParser = new OsmIncidentParser(dictionary);
        this.ospfParser = new OspfIncidentParser(dictionary);
        this.adlinkParser = new AdlinkIncidentParser(dictionary);
    }

    /**
     * Читає повідомлення з IMAP і розбирає їх на інциденти, що охоплюють обидві
     * чергові зміни.
     *
     * @param isInteractive коли true, читає всі повідомлення теки, а не лише в межах дат
     * @param prevDutyBegin початок попередньої зміни
     * @param prevDutyEnd   кінець попередньої зміни
     * @param currDutyBegin початок поточної зміни
     * @param currDutyEnd   кінець поточної зміни
     * @return усі інциденти, знайдені в межах [prevDutyBegin, currDutyEnd]
     */
    public List<Incident> prepareImapFolder(boolean isInteractive,
                                            LocalDateTime prevDutyBegin,
                                            LocalDateTime prevDutyEnd,
                                            LocalDateTime currDutyBegin,
                                            LocalDateTime currDutyEnd) {
        long fromEpoch = prevDutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long toEpoch = currDutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        log.debug("Filter period: {} … {}", prevDutyBegin, currDutyEnd);

        List<RawMessage> rawMessages;
        try {
            rawMessages = reader.readMessages(config.isDebug(), fromEpoch, toEpoch);
        } catch (MessagingException e) {
            log.error("IMAP error: {}", e.getMessage());
            throw new RuntimeException("IMAP error: " + e.getMessage(), e);
        }

        List<RawMessage> deduped = deduplicateAdlink(rawMessages);

        List<Incident> incidents = new ArrayList<>();
        for (RawMessage msg : deduped) {
            // Перевірка вікна однакова для кожного джерела, тому застосовується один раз наперед.
            // Раніше гілка OSM фільтрувала вже після розбору, за Incident.messageTs — який усе одно
            // присвоюється з msg.unixDate(), тож результат не змінився, а повідомлення поза вікном
            // більше не розбираються лише для того, щоб бути відкинутими.
            if (msg.unixDate() < fromEpoch || msg.unixDate() > toEpoch) {
                log.debug("Skipping message (time filter): unixDate={}, subject={}",
                        msg.unixDate(), msg.subject());
                continue;
            }
            if (isPdMessage(msg.subject())) {
                pdParser.parse(msg).ifPresent(incidents::add);
            } else if (isOspfMessage(msg.subject())) {
                ospfParser.parse(msg).ifPresent(incidents::add);
            } else if (isAdlinkMessage(msg.subject())) {
                adlinkParser.parse(msg).ifPresent(incidents::add);
            } else if (isOsmMessage(msg.subject())) {
                osmParser.parse(msg).ifPresent(incidents::add);
            }
        }

        log.info("IMAP processing done: {} incidents", incidents.size());
        return incidents;
    }

    /**
     * Прибирає дублікати adlink-алертів: Zabbix надсилає кожен алерт двічі протягом
     * кількох секунд. Залишає перше входження кожної пари (subject, status) у межах
     * 60-секундного вікна. Дедуплікація виконується до фільтрації за черговою зміною,
     * тому дублікати на межі періодів (напр. 07:59:59 та 08:00:03) коректно згортаються
     * в межах ранішої зміни.
     */
    // Package-private (не private): юніт-тестується напряму з ClientTest у цьому пакеті,
    // згідно з правилом CLAUDE.md розширювати видимість для тестів, а не дублювати логіку.
    List<RawMessage> deduplicateAdlink(List<RawMessage> messages) {
        List<RawMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparingLong(RawMessage::unixDate));

        // Останній збережений timestamp за темою — той самий підхід на основі мапи, що й у TrapDeduplicator.
        // Попередня версія повторно сканувала список усіх раніше побачених adlink, що є
        // квадратичним; оскільки вхід уже відсортований за зростанням, важливий лише останній збережений.
        Map<String, Long> lastKept = new HashMap<>();
        List<RawMessage> result = new ArrayList<>();
        for (RawMessage msg : sorted) {
            if (!isAdlinkMessage(msg.subject())) {
                result.add(msg);
                continue;
            }
            Long previous = lastKept.get(msg.subject());
            if (previous == null || msg.unixDate() - previous > ADLINK_DEDUP_WINDOW_SEC) {
                lastKept.put(msg.subject(), msg.unixDate());
                result.add(msg);
            } else {
                log.debug("Deduplicating adlink message: subject={}, ts={}", msg.subject(), msg.unixDate());
            }
        }
        return result;
    }

    /** Повертає {@code true} для тем алертів ICMP-ping або перезавантаження пристрою, які обробляє {@link PdIncidentParser}. */
    boolean isPdMessage(String subject) {
        return subject.matches(".*(?:Unavailable by ICMP ping|has been restarted).*");
    }

    /** Повертає {@code true} для тем алертів зміни стану сусіда OSPF, які обробляє {@link OspfIncidentParser}. */
    boolean isOspfMessage(String subject) {
        return subject.contains("ospfNbrStateChange");
    }

    /** Повертає {@code true} для тем алертів сухого контакту Zabbix (adlink), які обробляє {@link AdlinkIncidentParser}. */
    boolean isAdlinkMessage(String subject) {
        return subject.contains("adlink") && subject.contains("- Fault");
    }

    /**
     * Повертає {@code true} для тем алертів втрати живлення SDH/OSM або каналу STM, які
     * обробляє {@link OsmIncidentParser}. У режимі debug також враховуються алерти STM-1.
     */
    boolean isOsmMessage(String subject) {
        String stmPattern = "2-9";
        if (config.isDebug()) {
            stmPattern = "1-9";
        }
        return subject.matches(".*(?:[Pp][Oo][Ww][Ee][Rr]|STM [Ss][Tt][Mm].?[" + stmPattern + "][0-9]*).*");
    }
}
