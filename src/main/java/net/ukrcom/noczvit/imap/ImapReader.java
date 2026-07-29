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
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.search.SearchTerm;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;

/**
 * Infrastructure: connects to an IMAP server and returns raw messages. No
 * business logic — callers decide what to do with the messages.
 */
@Slf4j
public class ImapReader {

    private static final DateTimeFormatter MESSAGE_HEADER_FORMATTER
            = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final Config config;

    public ImapReader(Config config) {
        this.config = config;
    }

    /**
     * Reads messages from the configured IMAP folder.
     *
     * @param fetchAll when true, retrieves all messages regardless of date
     * @param fromEpoch unix epoch lower bound (inclusive) for date-based
     * filtering
     * @param toEpoch unix epoch upper bound (inclusive) for date-based
     * filtering
     * @return parsed raw messages; never null
     * @throws jakarta.mail.MessagingException
     */
    public List<RawMessage> readMessages(boolean fetchAll, long fromEpoch, long toEpoch) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.imap.ssl.enable", config.isMailSsl());
        props.put("mail.imap.host", config.getMailHostname());
        props.put("mail.imap.port", config.isMailSsl() ? "993" : "143");
        props.put("mail.imap.timeout", "5000");

        List<RawMessage> result = new ArrayList<>();
        Session session = Session.getInstance(props);

        try (IMAPStore store = (IMAPStore) session.getStore(config.isMailSsl() ? "imaps" : "imap")) {
            log.debug("Connecting to IMAP server: {}:{}", config.getMailHostname(), config.isMailSsl() ? "993" : "143");
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
                    messages = folder.search(new SearchTerm() {
                        @Override
                        public boolean match(Message message) {
                            try {
                                Date sentDate = message.getSentDate();
                                long unixDate = sentDate.getTime() / 1000;
                                return unixDate >= fromEpoch && unixDate <= toEpoch;
                            } catch (MessagingException e) {
                                return false;
                            }
                        }
                    });
                }

                for (Message msg : messages) {
                    parseRawMessage(msg).ifPresent(result::add);
                }
                log.info("IMAP: read {} messages", result.size());
            }
        }
        return result;
    }

    private Optional<RawMessage> parseRawMessage(Message msg) {
        try {
            String[] dateHeaders = msg.getHeader("Date");
            if (dateHeaders == null || dateHeaders.length == 0) {
                return Optional.empty();
            }
            String dateStr = dateHeaders[0];
            String subject = msg.getSubject();
            if (subject == null) {
                return Optional.empty();
            }
            long unixDate;
            try {
                unixDate = OffsetDateTime.parse(dateStr, MESSAGE_HEADER_FORMATTER).toEpochSecond();
            } catch (DateTimeParseException e) {
                log.debug("Failed to parse date: {}", dateStr);
                return Optional.empty();
            }
            String body;
            try {
                body = extractText(msg);
            } catch (MessagingException | IOException e) {
                log.debug("Failed to get message body: {}", e.getMessage());
                body = "";
            }
            return Optional.of(new RawMessage(dateStr, unixDate, subject, body));
        } catch (MessagingException e) {
            log.warn("Failed to parse IMAP message header: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractText(Message message) throws MessagingException, IOException {
        if (message.isMimeType("text/plain")) {
            Object content = message.getContent();
            return switch (content) {
                case String s ->
                    s;
                case InputStream is ->
                    new String(is.readAllBytes(), "UTF-8");
                default ->
                    "";
            };
        }
        if (message.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) message.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain")) {
                    return (String) part.getContent();
                }
            }
        }
        return "";
    }
}
