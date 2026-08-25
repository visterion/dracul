package de.visterion.dracul.executor;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates V45__executor_position_leg.sql's backfill for the common case -- a single-tranche
 * position -- against the REAL migration (not a copy of its SQL): migrates to V44, inserts a
 * synthetic single-tranche row, migrates to latest (runs V45), and asserts the resulting leg.
 * Uses a standalone container + programmatic Flyway, mirroring
 * WatchlistTagNormalizeMigrationTest / V21WebhookIdempotencyMigrationIT. Only synthetic data
 * here -- the real seeded broker order ids/quantities live only in V45 itself.
 */
class ExecutorPositionLegBackfillIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withPrivilegedMode(true);

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void singleTrancheBackfillCarriesQtyDirectlyOntoOneOpenLeg() throws Exception {
        // 1) Migrate to V44 (one version before the leg table under test).
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("44"))
                .load()
                .migrate();

        long positionId;
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // 2) Insert a synthetic single-tranche position BEFORE V45 runs, so the backfill
            // (not a hand-written assertion) is what's under test.
            positionId = insertPosition(conn, "ACME", new BigDecimal("10"), "OPEN",
                    "ord-1", "stop-1", null, null);

            // 3) Migrate to latest (runs V45 -- creates executor_position_leg and backfills it).
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT tranche, entry_order_id, stop_order_id, qty, status
                    FROM executor_position_leg WHERE position_id = ?
                    """)) {
                ps.setLong(1, positionId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("exactly one leg for the single-tranche position").isTrue();
                    assertThat(rs.getInt("tranche")).isEqualTo(1);
                    assertThat(rs.getString("entry_order_id")).isEqualTo("ord-1");
                    assertThat(rs.getString("stop_order_id")).isEqualTo("stop-1");
                    assertThat(rs.getBigDecimal("qty")).isEqualByComparingTo("10");
                    assertThat(rs.getString("status")).isEqualTo("OPEN");
                    assertThat(rs.next()).as("no second leg for a single-tranche position").isFalse();
                }
            }
        }
    }

    static long insertPosition(Connection conn, String symbol, BigDecimal qty, String status,
            String brokerOrderId, String stopOrderId, String tranche2OrderId,
            String tranche2StopOrderId) throws Exception {
        String sql = """
                INSERT INTO executor_position
                    (connection, symbol, side, qty, entry_price, initial_stop, active_stop,
                     status, broker_order_id, stop_order_id, tranche2_order_id, tranche2_stop_order_id)
                VALUES ('depot-1', ?, 'BUY', ?, 100.00, 90.00, 95.00, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setBigDecimal(2, qty);
            ps.setString(3, status);
            ps.setString(4, brokerOrderId);
            ps.setString(5, stopOrderId);
            ps.setString(6, tranche2OrderId);
            ps.setString(7, tranche2StopOrderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("id");
            }
        }
    }
}
