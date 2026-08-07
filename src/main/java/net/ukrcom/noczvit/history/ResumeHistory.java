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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;

/**
 * Постійне сховище резюме змін від Claude на базі SQLite.
 *
 * <p>
 * Кожен звітний період ідентифікується парою {@code (period_from, period_to)}
 * (Unix epoch у секундах). На кожен період існує щонайбільше один запис —
 * повторні збереження для того самого періоду виконують оновлення на місці
 * через семантику UPSERT SQLite 3.24+, що безпечно для повторних запусків тестів.
 *
 * <p>
 * Для кожної операції відкривається й закривається нове з'єднання
 * (патерн "connection-per-operation"), щоб уникнути проблем із блокуванням
 * запису SQLite між викликами.
 *
 * <p>
 * DDL (застосовується при конструюванні):
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS resume_history (
 *     period_from  INTEGER NOT NULL,
 *     period_to    INTEGER NOT NULL,
 *     created_at   INTEGER NOT NULL,
 *     summary_text TEXT    NOT NULL,
 *     PRIMARY KEY (period_from, period_to)
 * )
 * }</pre>
 */
@Slf4j
public class ResumeHistory {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS resume_history (
                period_from  INTEGER NOT NULL,
                period_to    INTEGER NOT NULL,
                created_at   INTEGER NOT NULL,
                summary_text TEXT    NOT NULL,
                PRIMARY KEY (period_from, period_to)
            )
            """;

    private static final String SELECT_PREVIOUS = """
            SELECT period_from, period_to, created_at, summary_text
            FROM resume_history
            WHERE period_to < ?
            ORDER BY period_to DESC
            LIMIT 1
            """;

    private static final String UPSERT = """
            INSERT INTO resume_history (period_from, period_to, created_at, summary_text)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(period_from, period_to) DO UPDATE SET
                created_at   = excluded.created_at,
                summary_text = excluded.summary_text
            """;

    private final String jdbcUrl;

    /**
     * Створює сховище та ініціалізує таблицю {@code resume_history}, якщо вона
     * ще не існує.
     *
     * @param jdbcUrl JDBC URL файлу SQLite, наприклад
     * {@code jdbc:sqlite:/var/lib/noczvit/history.db}
     * @throws SQLException якщо базу даних неможливо відкрити або DDL завершується помилкою
     */
    public ResumeHistory(String jdbcUrl) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            try (var pragmaStmt = conn.createStatement()) {
                pragmaStmt.execute("PRAGMA journal_mode = WAL");
                pragmaStmt.execute("PRAGMA busy_timeout = 30000");
            }
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA auto_vacuum = INCREMENTAL");
                st.execute(DDL);
                log.debug("ResumeHistory: таблицю перевірено/створено у {}", jdbcUrl);
            }
        }

    }

    /**
     * Повертає найновіше резюме, чий звітний період завершився до
     * {@code currentFrom}, або {@code null}, якщо такого запису не існує.
     *
     * @param currentFrom Unix epoch (секунди) початку поточного звітного
     * періоду; записи з {@code period_to >= currentFrom} виключаються
     * @return останній попередній {@link ResumeRecord}, або {@code null}
     * @throws SQLException якщо запит завершується помилкою
     */
    public ResumeRecord findPrevious(long currentFrom) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl); PreparedStatement ps = conn.prepareStatement(SELECT_PREVIOUS)) {
            ps.setLong(1, currentFrom);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ResumeRecord(
                            rs.getLong("period_from"),
                            rs.getLong("period_to"),
                            rs.getLong("created_at"),
                            rs.getString("summary_text"));
                }
            }
        }
        return null;
    }

    /**
     * Зберігає (або оновлює) текстове резюме Claude для заданого звітного
     * періоду.
     *
     * <p>
     * Якщо запис для {@code (periodFrom, periodTo)} вже існує, він оновлюється
     * на місці; ідентичність рядка (primary key) зберігається.
     *
     * @param periodFrom Unix epoch (секунди) початку звітного періоду
     * @param periodTo Unix epoch (секунди) кінця звітного періоду
     * @param summaryText текстове резюме Claude у форматі plain-text (без HTML)
     * @throws SQLException якщо запис завершується помилкою
     */
    public void save(long periodFrom, long periodTo, String summaryText) throws SQLException {
        long now = System.currentTimeMillis() / 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl); PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setLong(1, periodFrom);
            ps.setLong(2, periodTo);
            ps.setLong(3, now);
            ps.setString(4, summaryText);
            ps.executeUpdate();
            log.debug("ResumeHistory: збережено резюме для periodFrom={}, periodTo={}", periodFrom, periodTo);
        }
    }
}
