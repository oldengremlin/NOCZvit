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
 * Persistent store for Claude shift summaries backed by a SQLite database.
 *
 * <p>
 * Each reporting period is identified by {@code (period_from, period_to)} (Unix
 * epoch seconds). At most one record exists per period — subsequent saves for
 * the same period perform an in-place update via SQLite 3.24+ UPSERT semantics,
 * which is safe for test re-runs.
 *
 * <p>
 * A new connection is opened and closed for every operation
 * (connection-per-operation pattern) to avoid SQLite write-lock issues between
 * invocations.
 *
 * <p>
 * DDL (applied on construction):
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
     * Creates the store and initialises the {@code resume_history} table if it
     * does not exist.
     *
     * @param jdbcUrl JDBC URL of the SQLite file, e.g.
     * {@code jdbc:sqlite:/var/lib/noczvit/history.db}
     * @throws SQLException if the database cannot be opened or the DDL fails
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
     * Returns the most recent summary whose reporting period ended before
     * {@code currentFrom}, or {@code null} when no such record exists.
     *
     * @param currentFrom Unix epoch (seconds) start of the current reporting
     * period; records with {@code period_to >= currentFrom} are excluded
     * @return the latest previous {@link ResumeRecord}, or {@code null}
     * @throws SQLException if the query fails
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
     * Saves (or updates) the plain-text Claude summary for the given reporting
     * period.
     *
     * <p>
     * If a record for {@code (periodFrom, periodTo)} already exists it is
     * updated in place; the row identity (primary key) is preserved.
     *
     * @param periodFrom Unix epoch (seconds) start of the reporting period
     * @param periodTo Unix epoch (seconds) end of the reporting period
     * @param summaryText plain-text Claude summary (no HTML)
     * @throws SQLException if the write fails
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
