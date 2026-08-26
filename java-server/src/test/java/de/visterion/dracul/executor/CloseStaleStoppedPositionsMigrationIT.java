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
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates V46__close_stale_stopped_positions.sql against the REAL migration (not a copy of its
 * SQL): migrates to V45 (leg table exists, both synthetic positions still OPEN with their two
 * live legs), migrates to latest (runs V46), and asserts both positions, all four legs and both
 * cooldown rows land exactly as the broker's order-activity record demands. Also re-executes the
 * actual V46 file a second time to prove a re-run is a no-op.
 *
 * <p>Every id/quantity/price/R figure used below comes from {@link V46Facts}, which parses V46's
 * own "-- FACT ..." comment lines at setup time -- none of it is retyped into this file. That
 * keeps the real broker data quoted in exactly one place (the migration itself), matching the
 * standing rule on this branch (see V45's ACME/stop-1 synthetic test data for the same convention).
 */
class CloseStaleStoppedPositionsMigrationIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withPrivilegedMode(true);
    private static final Duration COOLDOWN_DAYS = Duration.ofDays(10);
    /** A position V46 must not touch at all. Id and symbol are synthetic and cannot collide with
     *  the two the migration names. */
    private static final long CONTROL_ID = 99L;
    private static final String CONTROL_SYMBOL = "ACME";

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

            // 2) Insert the two OPEN positions (ids/symbols/quantities parsed off V46 itself) with
            // their two live OPEN legs each -- exactly the shape the real book was in before this
            // migration ran in production.
            for (V46Facts.Position p : V46Facts.positions().values()) {
                insertPosition(conn, p.id(), p.symbol(), p.qty());
            }
            for (V46Facts.Leg leg : V46Facts.legs()) {
                insertLeg(conn, leg.positionId(), leg.tranche(), leg.stopOrderId(), leg.qty());
            }

            // 2b) A CONTROL position with its own open leg. V46 narrows every read and every
            // write to id 7 or id 12; without a row that must survive, a V46 whose WHERE
            // narrowing broke -- or whose leg UPDATE lost its `position_id =` -- would close
            // every open position in the book and this test would still pass.
            insertPosition(conn, CONTROL_ID, CONTROL_SYMBOL, new BigDecimal("13"));
            insertLeg(conn, CONTROL_ID, 1, "stop-control-1", new BigDecimal("13"));

            // 3) Migrate to latest -- runs V46.
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            for (V46Facts.Position p : V46Facts.positions().values()) {
                assertPosition(conn, p);
                assertCooldown(conn, p.symbol(), p.closedAt().plus(COOLDOWN_DAYS));
            }
            for (V46Facts.Leg leg : V46Facts.legs()) {
                V46Facts.Position owner = V46Facts.positions().get(leg.positionId());
                assertLegClosed(conn, leg.positionId(), leg.stopOrderId(), owner.exitPrice());
            }

            assertControlUntouched(conn);

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

            for (V46Facts.Position p : V46Facts.positions().values()) {
                assertPosition(conn, p);
            }
            assertThat(countCooldowns(conn))
                    .as("re-run must not write a second cooldown row")
                    .isEqualTo(cooldownCountBefore);
            // The control row must survive the re-run too: the no-op branch reads a position and
            // must not fall through to any write.
            assertControlUntouched(conn);
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

    private static void assertPosition(Connection conn, V46Facts.Position expected) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT status, exit_price, exit_reason, exit_price_source, realized_r, r_value, closed_at
                FROM executor_position WHERE id = ?
                """)) {
            ps.setLong(1, expected.id());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("position %d exists", expected.id()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("CLOSED");
                assertThat(rs.getBigDecimal("exit_price")).isEqualByComparingTo(expected.exitPrice());
                assertThat(rs.getString("exit_reason")).isEqualTo("HARD_STOP");
                assertThat(rs.getString("exit_price_source")).isEqualTo("FILL");
                assertThat(rs.getBigDecimal("realized_r")).isEqualByComparingTo(expected.realizedR());
                assertThat(rs.getBigDecimal("r_value")).isEqualByComparingTo(expected.rValue());
                assertThat(rs.getTimestamp("closed_at").toInstant()).isEqualTo(expected.closedAt());
            }
        }
    }

    private static void assertLegClosed(Connection conn, long positionId, String stopOrderId,
            BigDecimal exitPrice) throws Exception {
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

    private static void assertCooldown(Connection conn, String symbol, Instant expiresAt)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT reason, expires_at, exception_condition FROM cooldown WHERE symbol = ?
                """)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("cooldown row for %s exists", symbol).isTrue();
                assertThat(rs.getString("reason")).isEqualTo("HARD_STOP");
                assertThat(rs.getString("exception_condition")).isEqualTo("fresh setup only");
                assertThat(rs.getTimestamp("expires_at").toInstant()).isEqualTo(expiresAt);
                assertThat(rs.next()).as("exactly one cooldown row for %s", symbol).isFalse();
            }
        }
    }

    /** The control position and its leg are still exactly as inserted, and no cooldown was
     *  written for it. */
    private static void assertControlUntouched(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT status, exit_price, exit_reason, exit_price_source, realized_r, closed_at
                FROM executor_position WHERE id = ?
                """)) {
            ps.setLong(1, CONTROL_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("control position exists").isTrue();
                assertThat(rs.getString("status")).as("control position must stay OPEN")
                        .isEqualTo("OPEN");
                assertThat(rs.getBigDecimal("exit_price")).isNull();
                assertThat(rs.getString("exit_reason")).isNull();
                assertThat(rs.getString("exit_price_source")).isNull();
                assertThat(rs.getBigDecimal("realized_r")).isNull();
                assertThat(rs.getTimestamp("closed_at")).isNull();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT status, exit_price, exit_reason, closed_at FROM executor_position_leg
                WHERE position_id = ?
                """)) {
            ps.setLong(1, CONTROL_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("control leg exists").isTrue();
                assertThat(rs.getString("status")).as("control leg must stay OPEN").isEqualTo("OPEN");
                assertThat(rs.getBigDecimal("exit_price")).isNull();
                assertThat(rs.getString("exit_reason")).isNull();
                assertThat(rs.getTimestamp("closed_at")).isNull();
                assertThat(rs.next()).as("exactly one control leg").isFalse();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM cooldown WHERE symbol = ?")) {
            ps.setString(1, CONTROL_SYMBOL);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("no cooldown for the control symbol").isZero();
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
