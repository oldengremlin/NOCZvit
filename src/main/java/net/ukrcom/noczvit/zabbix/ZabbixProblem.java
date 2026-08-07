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
package net.ukrcom.noczvit.zabbix;

/**
 * Подія (проблема) Zabbix, отримана через API problem.get.
 *
 * @param host коротке ім'я хоста ураженого вузла
 * @param name назва проблеми (опис тригера)
 * @param clock unix-епоха початку проблеми
 * @param rClock unix-епоха вирішення проблеми (0 = ще активна)
 */
public record ZabbixProblem(String host, String name, long clock, long rClock) {

    /** Повертає {@code true}, якщо проблему ще не вирішено ({@code rClock == 0}). */
    public boolean isActive() {
        return rClock == 0;
    }
}
