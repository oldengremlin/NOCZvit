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

import java.time.Instant;

/**
 * Одна нормалізована подія SNMP-трапу, розібрана з IMAP-листа.
 *
 * @param timestamp   коли трап було отримано (з заголовка листа {@code Date:})
 * @param ip          IP-адреса джерела, як зазначено в тілі листа
 * @param hostname    hostname пристрою, витягнутий з теми листа
 * @param trapType    нормалізований рядок типу трапу (лапки прибрано, {@code ": "} → {@code ":"})
 * @param deviceClass {@link #CLASS_ADC} або {@link #CLASS_PDC}
 */
public record TrapEvent(Instant timestamp, String ip, String hostname, String trapType, String deviceClass) {

    public static final String CLASS_ADC = "adc";
    public static final String CLASS_PDC = "pdc";
}
