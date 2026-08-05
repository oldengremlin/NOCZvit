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
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.imap.ImapReader;
import net.ukrcom.noczvit.imap.MailMessageSupport;
import net.ukrcom.noczvit.imap.RawMessage;

/**
 * Reads SNMP trap emails from one or more IMAP folders (supports wildcard patterns
 * like {@code DC-Room*}) and returns them as {@link RawMessage} objects.
 *
 * <p>The folder pattern in {@code snmp.trap.folder} may be a literal folder path or
 * may end with {@code *} to match multiple sibling folders.
 *
 * <p>Connection setup and message conversion are shared with {@link ImapReader} via
 * {@link MailMessageSupport}; only folder-wildcard resolution is specific to this reader.
 */
@Slf4j
public class ImapTrapReader {

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
                        // Server-side SEARCH; see ImapReader.dateRangeTerm for why an anonymous
                        // SearchTerm must not be used here (it downloads the entire folder).
                        messages = imapFolder.search(ImapReader.dateRangeTerm(fromEpoch, toEpoch));
                    }

                    for (Message msg : messages) {
                        // Trap mails carry no In-Reply-To pairing — pass false to store an empty key
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

}
