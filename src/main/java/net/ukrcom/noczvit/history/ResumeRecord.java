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
 * Immutable snapshot of a previously generated Claude shift summary, read from the
 * {@code resume_history} SQLite table.
 *
 * @param periodFrom  Unix epoch (seconds) of the reporting period start
 * @param periodTo    Unix epoch (seconds) of the reporting period end
 * @param createdAt   Unix epoch (seconds) when this record was saved
 * @param summaryText plain-text Claude summary (no HTML markup)
 */
public record ResumeRecord(long periodFrom, long periodTo, long createdAt, String summaryText) {}
