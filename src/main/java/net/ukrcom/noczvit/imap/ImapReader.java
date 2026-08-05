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
 * Infrastructure: connects to an IMAP server and returns raw messages. No
 * business logic — callers decide what to do with the messages.
 *
 * <p>Connection setup and message conversion are shared with {@code trap.ImapTrapReader}
 * via {@link MailMessageSupport}.
 */
@Slf4j
public class ImapReader {

    private final Config config;

    /**
     * Creates a reader bound to the given configuration.
     */
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
     * Builds a server-side {@code SEARCH} term for the given epoch range.
     *
     * <p>Only standard terms are translated into an IMAP {@code SEARCH} command; an anonymous
     * {@link jakarta.mail.search.SearchTerm} subclass silently falls back to
     * {@link jakarta.mail.Folder#search}, which downloads <em>every</em> message in the folder
     * and calls {@code getSentDate()} on each — the single most expensive operation of a run.
     *
     * <p>IMAP {@code SEARCH} compares dates at day granularity, so the range is padded by one day
     * on each side to stay immune to server/client timezone differences. Exact second-level
     * trimming already happens downstream (see {@code imap.Client} and {@code NOCZvit}).
     */
    public static SearchTerm dateRangeTerm(long fromEpoch, long toEpoch) {
        return new AndTerm(
                new SentDateTerm(ComparisonTerm.GE, new Date((fromEpoch - 86400) * 1000)),
                new SentDateTerm(ComparisonTerm.LE, new Date((toEpoch + 86400) * 1000)));
    }

}
