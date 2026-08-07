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
import java.util.Set;

/**
 * Одна точкова подія датчика довкілля RAMOS, розібрана з IMAP-листа з трапом.
 *
 * @param timestamp  коли сталася подія датчика (визначено з рядка заголовка в тілі листа)
 * @param ip         IP-адреса джерела — пристрою RAMOS
 * @param state      рядок стану тривоги (наприклад, "High Critical", "Low Warning")
 * @param sensorName зрозуміла людині назва датчика (кириличні hex-назви вже декодовані)
 * @param sensorType тип датчика з MIB (наприклад, "Dual Temperature N", "Dry Contact N.M")
 * @param room       нормалізована мітка залу: "Room1"–"Room4", або "Інші", якщо не збіглося
 */
public record RamosTrapEvent(
        Instant timestamp,
        String ip,
        String state,
        String sensorName,
        String sensorType,
        String room
) {

    /**
     * Стани, що передаються в промпт до Claude — свідомо не просто «рівень Critical».
     *
     * <p>Зростання й падіння температури в дата-центрі не є симетричними ризиками: падіння
     * температури ({@code Low Warning}/{@code Low Critical}) рідко є проблемою, вартою уваги
     * інженера, бо все інше в залі зазвичай і так працює в тепловому режимі — тому обидва
     * винятки виключено навіть на рівні Critical. Зростання температури ({@code High Warning}/
     * {@code High Critical}) — навпаки: вартий уваги вже з першого попередження, до того як
     * стане критичним. Прості {@code Critical}/{@code Warning} (без префікса High/Low) належать
     * ненапрямленим датчикам — детекторам води, сухим контактам — де сам стан уже інформативний
     * незалежно від напрямку.
     */
    public static final Set<String> CLAUDE_STATES =
            Set.of("Critical", "High Critical", "Warning", "High Warning");

    /** Стани, які взагалі варто розбирати; усе інше — нормальна робота, тому відкидається. */
    public static final Set<String> REPORTABLE_STATES = Set.of(
            "Critical", "High Critical", "Low Critical",
            "High Warning", "Low Warning", "Warning",
            "Sensor Error");
}
