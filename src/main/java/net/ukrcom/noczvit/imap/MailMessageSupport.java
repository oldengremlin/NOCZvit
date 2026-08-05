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

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;

/**
 * Shared IMAP connection setup and message-to-{@link RawMessage} conversion, used by both
 * {@link ImapReader} (Zabbix/OSM alert folder) and {@code trap.ImapTrapReader} (SNMP trap folders).
 *
 * <p><b>Thread safety:</b> stateless — every method is static and works only on its arguments and
 * locals. This matters because the report run opens several IMAP branches concurrently on virtual
 * threads (Emerson traps and RAMOS traps each construct their own reader), so these methods are
 * called in parallel. {@link DateTimeFormatter} is immutable and safe to share; a fresh
 * {@link Properties} instance is returned per call rather than a shared constant.
 */
@Slf4j
public final class MailMessageSupport {

    // RFC 2822 allows single-digit day (e.g. "Thu, 9 Jul 2026") — use 'd' not 'dd'
    private static final DateTimeFormatter MESSAGE_HEADER_FORMATTER
            = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private MailMessageSupport() {
    }

    /**
     * Builds the jakarta.mail session properties for the configured IMAP server, including
     * connection/read/write timeouts.
     *
     * @param config source of hostname and SSL settings
     * @return a fresh mutable {@link Properties} owned by the caller
     */
    public static Properties imapProperties(Config config) {
        Properties props = new Properties();
        props.put("mail.imap.ssl.enable", config.isMailSsl());
        props.put("mail.imap.host", config.getMailHostname());
        props.put("mail.imap.port", imapPort(config));
        // getStore("imaps") makes jakarta.mail read the "mail.imaps." prefix, so timeouts
        // must be registered under the prefix that matches the protocol actually used.
        String p = config.isMailSsl() ? "mail.imaps." : "mail.imap.";
        props.put(p + "connectiontimeout", "10000");
        props.put(p + "timeout", "30000");
        props.put(p + "writetimeout", "30000");
        return props;
    }

    /** Returns the IMAP port matching the configured SSL mode. */
    public static String imapPort(Config config) {
        return config.isMailSsl() ? "993" : "143";
    }

    /** Returns the jakarta.mail store protocol name matching the configured SSL mode. */
    public static String imapProtocol(Config config) {
        return config.isMailSsl() ? "imaps" : "imap";
    }

    /**
     * Converts a Jakarta Mail {@link Message} to a {@link RawMessage}.
     * Returns empty if the {@code Date} header is missing, unparseable, or the subject is null.
     *
     * @param msg            message to convert
     * @param withInReplyTo  when true, the {@code In-Reply-To} header is carried over (used for
     *                       START/END pairing of alert mails); trap mails have no pairing and pass
     *                       false, which stores an empty key
     * @param logContext     short caller name used to attribute warnings to the right reader
     * @return converted message, or empty when it cannot be used
     */
    public static Optional<RawMessage> parseRawMessage(Message msg, boolean withInReplyTo,
                                                       String logContext) {
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
                // The strict pattern rejects RFC 5322 forms real MTAs emit — a trailing zone
                // comment ("+0300 (EEST)", added by Postfix), folding whitespace before a
                // single-digit day, a missing day-of-week or missing seconds. Dropping such
                // messages was silent (log.debug is off in production), so fall back to
                // jakarta.mail's lenient MailDateFormat instead.
                Date sent = msg.getSentDate();
                if (sent == null) {
                    log.warn("{}: unparseable Date header «{}» and no sent date, skipping message",
                            logContext, dateStr);
                    return Optional.empty();
                }
                unixDate = sent.getTime() / 1000;
                log.debug("{}: Date header «{}» not in strict format, used lenient parse",
                        logContext, dateStr);
            }
            String body;
            try {
                body = extractText(msg);
            } catch (MessagingException | IOException e) {
                log.debug("{}: failed to get message body: {}", logContext, e.getMessage());
                body = "";
            }
            String inReplyTo = "";
            if (withInReplyTo) {
                String[] replyToHeaders = msg.getHeader("In-Reply-To");
                inReplyTo = (replyToHeaders != null && replyToHeaders.length > 0)
                        ? replyToHeaders[0].trim() : "";
            }
            return Optional.of(new RawMessage(dateStr, unixDate, subject, body, inReplyTo));
        } catch (MessagingException e) {
            log.warn("{}: failed to parse IMAP message header: {}", logContext, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extracts the plain-text body from a message. Returns an empty string when no
     * {@code text/plain} part is found or the message has an unsupported MIME structure.
     */
    private static String extractText(Message message) throws MessagingException, IOException {
        if (message.isMimeType("text/plain")) {
            return contentAsText(message.getContent());
        }
        if (message.isMimeType("multipart/*")) {
            return extractText((Multipart) message.getContent());
        }
        return "";
    }

    /**
     * Walks a multipart tree depth-first and returns the first {@code text/plain} part found.
     *
     * <p>Recursion is required: a message carrying an attachment is typically
     * {@code multipart/mixed → multipart/alternative → text/plain}, and a flat scan of the top
     * level finds no {@code text/plain} part at all, silently yielding an empty body — which for
     * a trap mail means dropping the trap entirely.
     */
    private static String extractText(Multipart multipart) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("text/plain")) {
                return contentAsText(part.getContent());
            }
            if (part.getContent() instanceof Multipart nested) {
                String text = extractText(nested);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    /** Normalises a MIME part payload to text; {@code InputStream} parts are read as UTF-8. */
    private static String contentAsText(Object content) throws IOException {
        return switch (content) {
            case String s ->
                s;
            case InputStream is ->
                new String(is.readAllBytes(), StandardCharsets.UTF_8);
            default ->
                "";
        };
    }
}
