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

/**
 * Сире IMAP-повідомлення, зчитане з сервера — інфраструктурний DTO, без бізнес-логіки.
 *
 * @param dateStr    оригінальне значення заголовка {@code Date:} з IMAP-повідомлення
 * @param unixDate   unix-епоха, обчислена з {@code dateStr}
 * @param subject    рядок теми повідомлення (ніколи не null при створенні через {@link ImapReader})
 * @param body       текстове тіло; порожній рядок, якщо частину {@code text/plain} не знайдено
 * @param inReplyTo  значення заголовка {@code In-Reply-To:}; порожній рядок, якщо відсутній
 */
public record RawMessage(String dateStr, long unixDate, String subject, String body, String inReplyTo) {

}
