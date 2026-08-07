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
import java.util.List;

/**
 * Скорельований інцидент трапів, що представляє одну логічну подію на одному пристрої.
 *
 * @param deviceClass  {@link TrapEvent#CLASS_ADC} або {@link TrapEvent#CLASS_PDC}
 * @param hostname     hostname пристрою
 * @param ip           IP-адреса пристрою
 * @param severity     важливість події
 * @param activatedAt  коли подія почалась
 * @param clearedAt    коли подія завершилась; {@code null}, якщо досі відкрита наприкінці зміни
 * @param description  україномовний опис події
 * @param details      опціональні додаткові рядки деталей (наприклад, супутні трапи, що спрацювали)
 */
public record TrapIncident(
        String deviceClass,
        String hostname,
        String ip,
        Severity severity,
        Instant activatedAt,
        Instant clearedAt,
        String description,
        List<String> details) {

    public enum Severity {
        ALARM, WARNING, INFO
    }

    public boolean isClosed() {
        return clearedAt != null;
    }

    /** Витягує ідентифікатор залу з hostname на кшталт {@code adc-r1-1} → {@code r1}. */
    public String roomId() {
        String[] parts = hostname.split("-");
        return parts.length >= 2 ? parts[1] : "";
    }
}
