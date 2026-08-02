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
import jakarta.mail.Multipart;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.search.SearchTerm;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.imap.RawMessage;

/**
 * Reads SNMP trap emails from one or more IMAP folders (supports wildcard patterns
 * like {@code DC-Room*}) and returns them as {@link RawMessage} objects.
 *
 * <p>The folder pattern in {@code snmp.trap.folder} may be a literal folder path or
 * may end with {@code *} to match multiple sibling folders.
 */
@Slf4j
public class ImapTrapReader {

    // RFC 2822 allows single-digit day (e.g. "Thu, 9 Jul 2026") — use 'd' not 'dd'
    private static final DateTimeFormatter MESSAGE_HEADER_FORMATTER
            = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final Config config;

    /**
     * Creates a reader bound to the given configuration.
     */
    public ImapTrapReader(Config config) {
        this.config = config;
    }

    /**
     * Reads trap emails from all folders matching the configured {@code snmp.trap.folder} pattern.
     *
     * @param fetchAll  when true, retrieves all messages regardless of date
     * @param fromEpoch unix epoch lower bound (inclusive) for date filtering
     * @param toEpoch   unix epoch upper bound (inclusive) for date filtering
     * @return list of raw messages; never null
     * @throws MessagingException on IMAP errors
     */
    public List<RawMessage> readTraps(boolean fetchAll, long fromEpoch, long toEpoch)
            throws MessagingException {
        return readTrapsFromFolder(fetchAll, fromEpoch, toEpoch, config.getSnmpTrapFolder());
    }

    /**
     * Reads trap emails from all folders matching the given {@code folderPattern}.
     *
     * <p>The pattern may be a literal folder path or may end with {@code *} to match multiple
     * sibling folders (e.g. {@code INBOX/Internal/SNMP Traps/DC-Room*}).
     *
     * @param fetchAll      when true, retrieves all messages regardless of date
     * @param fromEpoch     unix epoch lower bound (inclusive) for date filtering
     * @param toEpoch       unix epoch upper bound (inclusive) for date filtering
     * @param folderPattern IMAP folder path or wildcard pattern to read from
     * @return list of raw messages; never null
     * @throws MessagingException on IMAP errors
     */
    public List<RawMessage> readTrapsFromFolder(boolean fetchAll, long fromEpoch, long toEpoch,
                                                String folderPattern) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.imap.ssl.enable", config.isMailSsl());
        props.put("mail.imap.host", config.getMailHostname());
        props.put("mail.imap.port", config.isMailSsl() ? "993" : "143");
        // getStore("imaps") makes jakarta.mail read the "mail.imaps." prefix, so timeouts
        // must be registered under the prefix that matches the protocol actually used.
        String p = config.isMailSsl() ? "mail.imaps." : "mail.imap.";
        props.put(p + "connectiontimeout", "10000");
        props.put(p + "timeout", "30000");
        props.put(p + "writetimeout", "30000");

        List<RawMessage> result = new ArrayList<>();
        Session session = Session.getInstance(props);

        try (IMAPStore store = (IMAPStore) session.getStore(config.isMailSsl() ? "imaps" : "imap")) {
            log.debug("ImapTrapReader: connecting to {}:{}", config.getMailHostname(),
                    config.isMailSsl() ? "993" : "143");
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
                        messages = imapFolder.search(new SearchTerm() {
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
                    log.info("ImapTrapReader: read {} messages from «{}»",
                            result.size(), imapFolder.getFullName());
                }
            }
        }
        return result;
    }

    /**
     * Resolves folders from the store that match {@code pattern}.
     *
     * <p>If the pattern contains a {@code *} character, the path is split at the last separator
     * before the wildcard and {@link Folder#list(String)} is called on the parent. Otherwise
     * the folder is opened directly.
     */
    private List<Folder> resolveFolders(IMAPStore store, String pattern) throws MessagingException {
        List<Folder> result = new ArrayList<>();

        char sep = store.getDefaultFolder().getSeparator();

        // Normalize: accept '/' as universal separator regardless of what the server uses.
        // Replace '/' with the server separator so store.getFolder() receives a valid path.
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
            for (Folder f : matched) {
                result.add(f);
            }
        }

        if (result.isEmpty()) {
            log.warn("ImapTrapReader: no folders matched pattern «{}»", pattern);
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
                log.debug("ImapTrapReader: failed to parse date «{}»", dateStr);
                return Optional.empty();
            }
            String body;
            try {
                body = extractText(msg);
            } catch (MessagingException | IOException e) {
                log.debug("ImapTrapReader: failed to get message body: {}", e.getMessage());
                body = "";
            }
            return Optional.of(new RawMessage(dateStr, unixDate, subject, body, ""));
        } catch (MessagingException e) {
            log.warn("ImapTrapReader: failed to parse message header: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractText(Message message) throws MessagingException, IOException {
        if (message.isMimeType("text/plain")) {
            Object content = message.getContent();
            return switch (content) {
                case String s -> s;
                case InputStream is -> new String(is.readAllBytes(), "UTF-8");
                default -> "";
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
