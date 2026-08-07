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

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.imap.ImapReader;
import net.ukrcom.noczvit.imap.MailMessageSupport;
import net.ukrcom.noczvit.imap.RawMessage;

/**
 * Читає листи SNMP-трапів з однієї чи кількох IMAP-тек (підтримує шаблони з {@code *},
 * наприклад {@code DC-Room*}) і повертає їх у вигляді об'єктів {@link RawMessage}.
 *
 * <p>Шаблон теки в {@code snmp.trap.folder} може бути буквальним шляхом до теки або
 * закінчуватись на {@code *}, щоб збігатись з кількома сусідніми теками.
 *
 * <p>Налаштування з'єднання та конвертація повідомлень спільні з {@link ImapReader} через
 * {@link MailMessageSupport}; специфічним для цього рідера є лише розв'язання шаблонів тек.
 */
@Slf4j
public class ImapTrapReader {

    private final Config config;

    /**
     * Створює рідер, прив'язаний до заданої конфігурації.
     * @param config
     */
    public ImapTrapReader(Config config) {
        this.config = config;
    }

    /**
     * Читає листи трапів з усіх тек, що відповідають налаштованому шаблону {@code snmp.trap.folder}.
     *
     * @param fetchAll  якщо true, отримує всі повідомлення незалежно від дати
     * @param fromEpoch нижня межа (включно) unix epoch для фільтрації за датою
     * @param toEpoch   верхня межа (включно) unix epoch для фільтрації за датою
     * @return список сирих повідомлень; ніколи не null
     * @throws MessagingException при помилках IMAP
     */
    public List<RawMessage> readTraps(boolean fetchAll, long fromEpoch, long toEpoch)
            throws MessagingException {
        return readTrapsFromFolder(fetchAll, fromEpoch, toEpoch, config.getSnmpTrapFolder());
    }

    /**
     * Читає листи трапів з усіх тек, що відповідають заданому {@code folderPattern}.
     *
     * <p>Шаблон може бути буквальним шляхом до теки або закінчуватись на {@code *}, щоб
     * збігатись з кількома сусідніми теками (напр. {@code INBOX/Internal/SNMP Traps/DC-Room*}).
     *
     * @param fetchAll      якщо true, отримує всі повідомлення незалежно від дати
     * @param fromEpoch     нижня межа (включно) unix epoch для фільтрації за датою
     * @param toEpoch       верхня межа (включно) unix epoch для фільтрації за датою
     * @param folderPattern шлях IMAP-теки або шаблон з {@code *}, з якого читати
     * @return список сирих повідомлень; ніколи не null
     * @throws MessagingException при помилках IMAP
     */
    public List<RawMessage> readTrapsFromFolder(boolean fetchAll, long fromEpoch, long toEpoch,
                                                String folderPattern) throws MessagingException {
        Properties props = MailMessageSupport.imapProperties(config);

        List<RawMessage> result = new ArrayList<>();
        Session session = Session.getInstance(props);

        try (IMAPStore store = (IMAPStore) session.getStore(MailMessageSupport.imapProtocol(config))) {
            log.debug("ImapTrapReader: connecting to {}:{}", config.getMailHostname(),
                    MailMessageSupport.imapPort(config));
            store.connect(config.getMailHostname(), config.getMailUsername(), config.getMailPassword());

            List<Folder> folders = resolveFolders(store, folderPattern);
            log.info("ImapTrapReader: found {} trap folder(s) matching «{}»",
                    folders.size(), folderPattern);

            for (Folder folder : folders) {
                try (IMAPFolder imapFolder = (IMAPFolder) folder) {
                    imapFolder.open(Folder.READ_ONLY);
                    int total = imapFolder.getMessageCount();
                    if (total == 0) {
                        log.debug("ImapTrapReader: folder «{}» is empty", imapFolder.getFullName());
                        continue;
                    }
                    log.info("ImapTrapReader: processing {} messages from «{}»",
                            total, imapFolder.getFullName());

                    Message[] messages;
                    if (fetchAll) {
                        messages = imapFolder.getMessages();
                    } else {
                        // SEARCH на боці сервера; див. ImapReader.dateRangeTerm — чому анонімний
                        // SearchTerm тут використовувати не можна (він завантажує всю теку).
                        messages = imapFolder.search(ImapReader.dateRangeTerm(fromEpoch, toEpoch));
                    }

                    for (Message msg : messages) {
                        // Листи трапів не мають пари In-Reply-To — передаємо false, щоб зберегти порожній ключ
                        MailMessageSupport.parseRawMessage(msg, false, "ImapTrapReader")
                                .ifPresent(result::add);
                    }
                    log.info("ImapTrapReader: read {} messages from «{}»",
                            result.size(), imapFolder.getFullName());
                }
            }
        }
        return result;
    }

    /**
     * Розв'язує теки зі сховища, що відповідають {@code pattern}.
     *
     * <p>Якщо шаблон містить символ {@code *}, шлях розбивається за останнім роздільником
     * перед шаблоном, і на батьківській теці викликається {@link Folder#list(String)}. Інакше
     * тека відкривається напряму.
     */
    private List<Folder> resolveFolders(IMAPStore store, String pattern) throws MessagingException {
        List<Folder> result = new ArrayList<>();

        char sep = store.getDefaultFolder().getSeparator();

        // Нормалізація: приймаємо '/' як універсальний роздільник незалежно від того, що
        // фактично використовує сервер. Замінюємо '/' на роздільник сервера, щоб
        // store.getFolder() отримав коректний шлях.
        String normalizedPattern = (sep != '/') ? pattern.replace('/', sep) : pattern;

        if (!normalizedPattern.contains("*")) {
            Folder f = store.getFolder(normalizedPattern);
            if (f.exists()) {
                result.add(f);
            } else {
                log.warn("ImapTrapReader: folder «{}» does not exist", pattern);
            }
            return result;
        }

        int lastSep = normalizedPattern.lastIndexOf(sep);
        String parentPath;
        String mask;
        if (lastSep >= 0) {
            parentPath = normalizedPattern.substring(0, lastSep);
            mask = normalizedPattern.substring(lastSep + 1);
        } else {
            parentPath = "";
            mask = normalizedPattern;
        }

        Folder parent = parentPath.isEmpty()
                ? store.getDefaultFolder()
                : store.getFolder(parentPath);

        Folder[] matched = parent.list(mask);

        if (matched != null) {
            result.addAll(Arrays.asList(matched));
        }

        if (result.isEmpty()) {
            log.warn("ImapTrapReader: no folders matched pattern «{}»", pattern);
        }
        return result;
    }

}
