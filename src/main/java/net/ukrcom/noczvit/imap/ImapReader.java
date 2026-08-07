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

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SentDateTerm;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;

/**
 * Інфраструктура: підключається до сервера IMAP і повертає сирі повідомлення. Без
 * бізнес-логіки — виклики самі вирішують, що робити з повідомленнями.
 *
 * <p>Налаштування з'єднання й перетворення повідомлень спільні з {@code trap.ImapTrapReader}
 * через {@link MailMessageSupport}.
 */
@Slf4j
public class ImapReader {

    private final Config config;

    /**
     * Створює читач, прив'язаний до заданої конфігурації.
     * @param config джерело налаштувань IMAP-з'єднання
     */
    public ImapReader(Config config) {
        this.config = config;
    }

    /**
     * Читає повідомлення з налаштованої теки IMAP.
     *
     * @param fetchAll коли true, отримує всі повідомлення незалежно від дати
     * @param fromEpoch нижня межа unix-епохи (включно) для фільтрації
     * за датою
     * @param toEpoch верхня межа unix-епохи (включно) для фільтрації
     * за датою
     * @return розібрані сирі повідомлення; ніколи не null
     * @throws jakarta.mail.MessagingException
     */
    public List<RawMessage> readMessages(boolean fetchAll, long fromEpoch, long toEpoch) throws MessagingException {
        Properties props = MailMessageSupport.imapProperties(config);

        List<RawMessage> result = new ArrayList<>();
        Session session = Session.getInstance(props);

        try (IMAPStore store = (IMAPStore) session.getStore(MailMessageSupport.imapProtocol(config))) {
            log.debug("Connecting to IMAP server: {}:{}", config.getMailHostname(),
                    MailMessageSupport.imapPort(config));
            store.connect(config.getMailHostname(), config.getMailUsername(), config.getMailPassword());
            log.debug("Connected to IMAP server");

            try (IMAPFolder folder = (IMAPFolder) store.getFolder(config.getZabbixFolder())) {
                folder.open(Folder.READ_ONLY);
                if (log.isDebugEnabled()) {
                    log.debug("IMAP folders:");
                    for (Folder f : store.getDefaultFolder().list()) {
                        log.debug("  {}", f.getFullName());
                    }
                }

                if (folder.getMessageCount() == 0) {
                    return result;
                }

                log.info("Processing {} messages from IMAP folder...", folder.getMessageCount());
                Message[] messages;
                if (fetchAll) {
                    messages = folder.getMessages();
                } else {
                    log.info("IMAP filter: sent >= {} && sent <= {}", fromEpoch, toEpoch);
                    messages = folder.search(dateRangeTerm(fromEpoch, toEpoch));
                }

                for (Message msg : messages) {
                    MailMessageSupport.parseRawMessage(msg, true, "ImapReader")
                            .ifPresent(result::add);
                }
                log.info("IMAP: read {} messages", result.size());
            }
        }
        return result;
    }

    /**
     * Будує серверний термін {@code SEARCH} для заданого діапазону епохи.
     *
     * <p>У команду IMAP {@code SEARCH} перекладаються лише стандартні терміни; анонімний
     * підклас {@link jakarta.mail.search.SearchTerm} мовчки відкочується до
     * {@link jakarta.mail.Folder#search}, який завантажує <em>кожне</em> повідомлення теки
     * і викликає {@code getSentDate()} для кожного — найдорожча операція за весь запуск.
     *
     * <p>IMAP {@code SEARCH} порівнює дати з точністю до дня, тому діапазон розширюється на
     * один день з кожного боку, щоб не залежати від різниці часових зон сервера й клієнта.
     * Точне обрізання до секунди вже відбувається далі (див. {@code imap.Client} та {@code NOCZvit}).
     * @param fromEpoch нижня межа unix-епохи (включно)
     * @param toEpoch   верхня межа unix-епохи (включно)
     * @return термін {@code SEARCH} для {@link Folder#search}
     */
    public static SearchTerm dateRangeTerm(long fromEpoch, long toEpoch) {
        return new AndTerm(
                new SentDateTerm(ComparisonTerm.GE, new Date((fromEpoch - 86400) * 1000)),
                new SentDateTerm(ComparisonTerm.LE, new Date((toEpoch + 86400) * 1000)));
    }

}
