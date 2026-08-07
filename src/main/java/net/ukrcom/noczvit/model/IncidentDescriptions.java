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

import net.ukrcom.noczvit.model.Incident.Status;

/**
 * Спільні формулювання для описів {@link Incident} та відображення теми сповіщення →
 * {@link Status}, які використовуються кожним джерелом інцидентів (чотирма IMAP-парсерами
 * та конвертером Zabbix API).
 *
 * <p>Зберігання цього в одному місці важливе для самого звіту: {@code IncidentSectionBuilder}
 * переписує опис спарованого рядка, замінюючи буквальні фрагменти {@code "початок інциденту, "} /
 * {@code "кінець інциденту, "}, тож будь-яке джерело, яке формулює свій префікс інакше,
 * непомітно зламало б парування.
 *
 * <p><b>Потокобезпека:</b> без стану — усі методи статичні й оперують лише своїми
 * аргументами. Джерела інцидентів виконуються одночасно на віртуальних потоках.
 */
public final class IncidentDescriptions {

    /** Мітка джерела для інцидентів, що походять від Zabbix (як IMAP-сповіщення, так і Zabbix API). */
    public static final String SOURCE_ZABBIX = "Zabbix";

    /** Мітка джерела для інцидентів, що походять від OSM/SDH. */
    public static final String SOURCE_OSM = "OSM";

    private IncidentDescriptions() {
    }

    /**
     * Відображає тему сповіщення на статус життєвого циклу: {@code " Resolved:"} → {@link Status#END},
     * {@code " Problem:"} → {@link Status#START}, все інше → {@link Status#NONE}.
     *
     * @param subject сира тема листа
     * @return розпізнаний статус; ніколи не null
     */
    public static Status resolveStatus(String subject) {
        if (subject.contains(" Resolved:")) {
            return Status.END;
        }
        if (subject.contains(" Problem:")) {
            return Status.START;
        }
        return Status.NONE;
    }

    /**
     * Формує префікс опису, наприклад {@code "Zabbix зареєстровано початок інциденту, "}.
     * {@link Status#NONE} дає просту форму {@code "<source> зареєстровано "}.
     *
     * @param source мітка джерела ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status статус життєвого циклу інциденту
     * @return префікс, що закінчується пробілом
     */
    public static String statePrefix(String source, Status status) {
        return statePrefix(source, status, "");
    }

    /**
     * Формує префікс опису з явним формулюванням для {@link Status#NONE}.
     *
     * @param source   мітка джерела ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status   статус життєвого циклу інциденту
     * @param noneText що йде після {@code "зареєстровано "} для {@link Status#NONE} — OSM пише
     *                 {@code "інцидент, "}, джерела Zabbix лишають порожнім
     * @return префікс, що закінчується пробілом
     */
    public static String statePrefix(String source, Status status, String noneText) {
        return source + " зареєстровано " + switch (status) {
            case START ->
                "початок інциденту, ";
            case END ->
                "кінець інциденту, ";
            case NONE ->
                noneText;
        };
    }

    /**
     * Збирає повний опис із префікса стану та фрази події, стискаючи послідовності пробілів —
     * фрази події складаються зі значень словника, які можуть мати зайві пробіли.
     *
     * @param source мітка джерела ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status статус життєвого циклу інциденту
     * @param event  фраза події (наприклад {@code "зникнення зв'язку з обладнанням на Прахових 50"})
     * @return нормалізований опис
     */
    public static String describe(String source, Status status, String event) {
        return describe(source, status, event, "");
    }

    /**
     * Збирає повний опис з явним формулюванням для {@link Status#NONE}.
     *
     * @param source   мітка джерела ({@link #SOURCE_ZABBIX} / {@link #SOURCE_OSM})
     * @param status   статус життєвого циклу інциденту
     * @param event    фраза події
     * @param noneText що йде після {@code "зареєстровано "} для {@link Status#NONE}
     * @return нормалізований опис
     */
    public static String describe(String source, Status status, String event, String noneText) {
        return (statePrefix(source, status, noneText) + event).replaceAll("\\s+", " ");
    }
}
