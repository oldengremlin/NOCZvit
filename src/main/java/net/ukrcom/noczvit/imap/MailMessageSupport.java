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
 * Спільне налаштування IMAP-з'єднання та конвертація повідомлення в {@link RawMessage},
 * використовується і в {@link ImapReader} (тека алертів Zabbix/OSM), і в
 * {@code trap.ImapTrapReader} (теки SNMP-трапів).
 *
 * <p><b>Потокобезпечність:</b> без стану — кожен метод статичний і працює лише зі своїми
 * аргументами та локальними змінними. Це важливо, бо під час формування звіту одночасно
 * відкривається кілька IMAP-гілок на віртуальних потоках (трапи Emerson і трапи RAMOS кожен
 * створюють власний reader), тож ці методи викликаються паралельно. {@link DateTimeFormatter}
 * незмінний і безпечний для спільного використання; свіжий екземпляр {@link Properties}
 * повертається на кожен виклик, а не спільна константа.
 */
@Slf4j
public final class MailMessageSupport {

    // RFC 2822 допускає одноцифровий день (напр. "Thu, 9 Jul 2026") — тому 'd', а не 'dd'
    private static final DateTimeFormatter MESSAGE_HEADER_FORMATTER
            = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private MailMessageSupport() {
    }

    /**
     * Формує властивості сесії jakarta.mail для налаштованого IMAP-сервера, включно з
     * таймаутами з'єднання/читання/запису.
     *
     * @param config джерело імені хоста та налаштувань SSL
     * @return свіжий мутабельний екземпляр {@link Properties}, яким володіє викликач
     */
    public static Properties imapProperties(Config config) {
        Properties props = new Properties();
        props.put("mail.imap.ssl.enable", config.isMailSsl());
        props.put("mail.imap.host", config.getMailHostname());
        props.put("mail.imap.port", imapPort(config));
        // getStore("imaps") змушує jakarta.mail читати префікс "mail.imaps.", тож таймаути
        // потрібно реєструвати під префіксом, що відповідає фактично використаному протоколу.
        String p = config.isMailSsl() ? "mail.imaps." : "mail.imap.";
        props.put(p + "connectiontimeout", "10000");
        props.put(p + "timeout", "30000");
        props.put(p + "writetimeout", "30000");
        return props;
    }

    /** Повертає IMAP-порт відповідно до налаштованого режиму SSL.
     * @param config джерело налаштувань
     * @return {@code "993"} для SSL, інакше {@code "143"}
     */
    public static String imapPort(Config config) {
        return config.isMailSsl() ? "993" : "143";
    }

    /** Повертає назву протоколу jakarta.mail store відповідно до налаштованого режиму SSL.
     * @param config джерело налаштувань
     * @return {@code "imaps"} для SSL, інакше {@code "imap"}
     */
    public static String imapProtocol(Config config) {
        return config.isMailSsl() ? "imaps" : "imap";
    }

    /**
     * Конвертує Jakarta Mail {@link Message} у {@link RawMessage}.
     * Повертає empty, якщо заголовок {@code Date} відсутній, не парситься, або тема (subject) null.
     *
     * @param msg            повідомлення для конвертації
     * @param withInReplyTo  якщо true, заголовок {@code In-Reply-To} переноситься (використовується
     *                       для парування START/END листів-алертів); листи-трапи парування не мають
     *                       і передають false, що зберігає порожній ключ
     * @param logContext     коротке ім'я викликача для прив'язки попереджень до потрібного reader
     * @return сконвертоване повідомлення, або empty, якщо його не можна використати
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
                // Строгий патерн відхиляє форми RFC 5322, які реально надсилають MTA — коментар
                // часового поясу в кінці ("+0300 (EEST)", доданий Postfix), пробіли перед
                // одноцифровим днем, відсутній день тижня чи відсутні секунди. Відкидання таких
                // повідомлень проходило непомітно (log.debug вимкнено в продакшені), тож натомість
                // використовуємо резервний варіант — поблажливий MailDateFormat з jakarta.mail.
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
     * Видобуває текстове тіло з повідомлення. Повертає порожній рядок, якщо частину
     * {@code text/plain} не знайдено або повідомлення має непідтримувану MIME-структуру.
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
     * Обходить дерево multipart углиб і повертає першу знайдену частину {@code text/plain}.
     *
     * <p>Рекурсія необхідна: повідомлення з вкладенням зазвичай має структуру
     * {@code multipart/mixed → multipart/alternative → text/plain}, і плоский прохід лише
     * верхнього рівня взагалі не знаходить частини {@code text/plain}, мовчки повертаючи
     * порожнє тіло — а для листа-трапу це означає повне відкидання трапу.
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

    /** Нормалізує вміст MIME-частини в текст; частини {@code InputStream} читаються як UTF-8. */
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
