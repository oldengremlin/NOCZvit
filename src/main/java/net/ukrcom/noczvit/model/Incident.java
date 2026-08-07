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
package net.ukrcom.noczvit.model;

import java.util.List;

/**
 * Доменна модель одного мережевого інциденту з джерела PD (Zabbix) або OSM
 * (SDH).
 *
 * @param location назва локації, розпізнана словником, використовується для
 * групування (наприклад "Обухів, Малишка 2")
 * @param device сирий ідентифікатор пристрою для PD-інцидентів; порожній рядок для OSM
 * @param messageTs unix epoch IMAP-повідомлення
 * @param eventTs unix epoch фактичної події (дорівнює messageTs для PD; береться
 * зі значення Trap для OSM)
 * @param messageDateStr локалізований рядок дати IMAP-повідомлення
 * @param eventDateStr локалізований рядок дати фактичної події (дорівнює
 * messageDateStr для PD)
 * @param source яка система виявила інцидент
 * @param status статус життєвого циклу інциденту
 * @param description опис події у форматі plain-text (без HTML)
 * @param reviewNames назви пристроїв/локацій, не знайдені у словнику; порожній
 * список, якщо все розпізнано
 * @param inReplyTo   ключ парування: заголовок {@code In-Reply-To:} для IMAP-інцидентів,
 * синтетичний {@code "zabbix:host:clock"} для інцидентів Zabbix API,
 * порожній рядок для непарних подій (OSM тощо)
 */
public record Incident(
        String location,
        String device,
        long messageTs,
        long eventTs,
        String messageDateStr,
        String eventDateStr,
        Source source,
        Status status,
        String description,
        List<String> reviewNames,
        String inReplyTo
        ) {

    /**
     * Визначає, яка система моніторингу згенерувала інцидент.
     * {@code PD} — email-сповіщення Zabbix, розібрані {@code PdIncidentParser} або {@code OspfIncidentParser};
     * {@code OSM} — trap-листи SDH/OSM, розібрані {@code OsmIncidentParser};
     * {@code ZABBIX} — події, отримані напряму з Zabbix API.
     */
    public enum Source {
        PD, OSM, ZABBIX
    }

    /**
     * Стан життєвого циклу інциденту.
     * {@code START} — початок проблеми; {@code END} — проблему вирішено;
     * {@code NONE} — інформаційна подія без чіткої пари початок/кінець.
     */
    public enum Status {
        START, END, NONE
    }
}
