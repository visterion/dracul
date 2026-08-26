package de.visterion.dracul.executor;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates V46__close_stale_stopped_positions.sql against the REAL migration (not a copy of its
 * SQL): migrates to V45 (leg table exists, both synthetic positions still OPEN with their two
 * live legs), migrates to latest (runs V46), and asserts both positions, all four legs and both
 * cooldown rows land exactly as the broker's order-activity record demands. Also re-executes the
 * actual V46 file a second time to prove a re-run is a no-op. Only synthetic ids/quantities here
 * -- the real broker order ids/prices/quantities from Saxo's record live only in V46 itself.
 */
class CloseStaleStoppedPositionsMigrationIT {

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
    void bothStalePositionsAreClosedWithLegsAndCooldownAndRerunIsNoop() throws Exception {
        // 1) Migrate to V45: the leg table exists, both synthetic positions are still OPEN.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("45"))
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // 2) Insert the two synthetic OPEN positions (ids 7 / 12, matching V46's WHERE
            // clauses) with their two live OPEN legs each, quantities summing to the position qty
            // -- exactly the shape the real book was in before this migration ran in production.
            insertPosition(conn, 7, "OFG", new BigDecimal("42"));
            insertLeg(conn, 7, 1, "5039387855", new BigDecimal("21"));
            insertLeg(conn, 7, 2, "5039471907", new BigDecimal("21"));

            insertPosition(conn, 12, "RGNX", new BigDecimal("209"));
            insertLeg(conn, 12, 1, "5039591743", new BigDecimal("107"));
            insertLeg(conn, 12, 2, "5039676276", new BigDecimal("102"));

            // 3) Migrate to latest -- runs V46.
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertPosition(conn, 7, "51.50", "-0.351", "2026-08-19T19:55:37Z");
            assertPosition(conn, 12, "8.29", "-1.13", "2026-08-24T13:30:17Z");

            assertLegClosed(conn, 7, "5039387855", "51.50");
            assertLegClosed(conn, 7, "5039471907", "51.50");
            assertLegClosed(conn, 12, "5039591743", "8.29");
            assertLegClosed(conn, 12, "5039676276", "8.29");

            assertCooldown(conn, "OFG", "2026-08-29T19:55:37Z");
            assertCooldown(conn, "RGNX", "2026-09-03T13:30:17Z");

            long cooldownCountBefore = countCooldowns(conn);

            // 4) Re-execute the actual V46 file a second time (not a hand-written copy) --
            // Flyway itself never re-applies an already-recorded version, so this is the only way
            // to prove the migration's own re-run guard, not Flyway's bookkeeping, is what makes a
            // second run a no-op.
            String sql = Files.readString(Path.of(
                    "src/main/resources/db/migration/V46__close_stale_stopped_positions.sql"));
            try (var stmt = conn.createStatement()) {
                stmt.execute(sql);
            }

            assertPosition(conn, 7, "51.50", "-0.351", "2026-08-19T19:55:37Z");
            assertPosition(conn, 12, "8.29", "-1.13", "2026-08-24T13:30:17Z");
            assertThat(countCooldowns(conn))
                    .as("re-run must not write a second cooldown row")
                    .isEqualTo(cooldownCountBefore);
        }
    }

    private static void insertPosition(Connection conn, long id, String symbol, BigDecimal qty)
            throws Exception {
        String sql = """
                INSERT INTO executor_position
                    (id, connection, symbol, side, qty, entry_price, initial_stop, active_stop, status)
                VALUES (?, 'depot-1', ?, 'BUY', ?, 100.00, 90.00, 95.00, 'OPEN')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, symbol);
            ps.setBigDecimal(3, qty);
            ps.executeUpdate();
        }
    }

    private static void insertLeg(Connection conn, long positionId, int tranche,
            String stopOrderId, BigDecimal qty) throws Exception {
        String sql = """
                INSERT INTO executor_position_leg (position_id, tranche, stop_order_id, qty, status)
                VALUES (?, ?, ?, ?, 'OPEN')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, positionId);
            ps.setInt(2, tranche);
            ps.setString(3, stopOrderId);
            ps.setBigDecimal(4, qty);
            ps.executeUpdate();
        }
    }

    private static void assertPosition(Connection conn, long id, String exitPrice,
            String realizedR, String closedAtIso) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT status, exit_price, exit_reason, exit_price_source, realized_r, closed_at
                FROM executor_position WHERE id = ?
                """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("position %d exists", id).isTrue();
                assertThat(rs.getString("status")).isEqualTo("CLOSED");
                assertThat(rs.getBigDecimal("exit_price")).isEqualByComparingTo(exitPrice);
                assertThat(rs.getString("exit_reason")).isEqualTo("HARD_STOP");
                assertThat(rs.getString("exit_price_source")).isEqualTo("FILL");
                assertThat(rs.getBigDecimal("realized_r")).isEqualByComparingTo(realizedR);
                assertThat(rs.getTimestamp("closed_at").toInstant())
                        .isEqualTo(OffsetDateTime.parse(closedAtIso).toInstant());
            }
        }
    }

    private static void assertLegClosed(Connection conn, long positionId, String stopOrderId,
            String exitPrice) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT status, exit_price, exit_reason FROM executor_position_leg
                WHERE position_id = ? AND stop_order_id = ?
                """)) {
            ps.setLong(1, positionId);
            ps.setString(2, stopOrderId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("leg %s for position %d exists", stopOrderId, positionId)
                        .isTrue();
                assertThat(rs.getString("status")).isEqualTo("CLOSED");
                assertThat(rs.getBigDecimal("exit_price")).isEqualByComparingTo(exitPrice);
                assertThat(rs.getString("exit_reason")).isEqualTo("HARD_STOP");
            }
        }
    }

    private static void assertCooldown(Connection conn, String symbol, String expiresAtIso)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT reason, expires_at, exception_condition FROM cooldown WHERE symbol = ?
                """)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("cooldown row for %s exists", symbol).isTrue();
                assertThat(rs.getString("reason")).isEqualTo("HARD_STOP");
                assertThat(rs.getString("exception_condition")).isEqualTo("fresh setup only");
                assertThat(rs.getTimestamp("expires_at").toInstant())
                        .isEqualTo(OffsetDateTime.parse(expiresAtIso).toInstant());
                assertThat(rs.next()).as("exactly one cooldown row for %s", symbol).isFalse();
            }
        }
    }

    private static long countCooldowns(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM cooldown");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
