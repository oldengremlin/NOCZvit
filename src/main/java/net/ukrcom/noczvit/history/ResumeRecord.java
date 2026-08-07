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
package net.ukrcom.noczvit.history;

/**
 * Незмінний знімок раніше згенерованого резюме зміни від Claude, прочитаний з
 * таблиці SQLite {@code resume_history}.
 *
 * @param periodFrom  Unix epoch (секунди) початку звітного періоду
 * @param periodTo    Unix epoch (секунди) кінця звітного періоду
 * @param createdAt   Unix epoch (секунди) моменту збереження цього запису
 * @param summaryText текстове резюме Claude у форматі plain-text (без HTML-розмітки)
 */
public record ResumeRecord(long periodFrom, long periodTo, long createdAt, String summaryText) {}
