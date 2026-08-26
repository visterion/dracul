package de.visterion.dracul.executor;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates V46__close_stale_stopped_positions.sql's fail-loud guards against the REAL migration
 * (not a copy of its SQL): if the book does not match the assumptions the migration is keyed on
 * -- position missing, or its legs not summing to its quantity -- it must abort by name rather
 * than write something approximate. Each scenario gets its own container: a failed migration
 * leaves Flyway's schema history dirty, mirroring ExecutorPositionLegBackfillGuardIT.
 */
class CloseStaleStoppedPositionsMigrationGuardIT {

    private PostgreSQLContainer<?> postgres;

    @BeforeEach
    void startContainer() {
        postgres = new PostgreSQLContainer<>("postgres:18-alpine").withPrivilegedMode(true);
        postgres.start();
    }

    @AfterEach
    void stopContainer() {
        postgres.stop();
    }

    @Test
    void positionIdPresentWithWrongSymbolAbortsTheMigrationByName() throws Exception {
        migrateToV45();
        // Position 7 exists but under a different symbol than the broker record names --
        // a from-scratch database missing the id entirely is a legitimate no-op (every fresh
        // Testcontainers/CI schema starts that way), but an id that DOES exist and disagrees with
        // the broker record must never be touched silently.
        try (Connection conn = connect()) {
            insertPosition(conn, 7, "NOTOFG", new BigDecimal("42"));
            insertLeg(conn, 7, 1, "5039387855", new BigDecimal("21"));
            insertLeg(conn, 7, 2, "5039471907", new BigDecimal("21"));

            insertPosition(conn, 12, "RGNX", new BigDecimal("209"));
            insertLeg(conn, 12, 1, "5039591743", new BigDecimal("107"));
            insertLeg(conn, 12, 2, "5039676276", new BigDecimal("102"));
        }

        assertThatThrownBy(this::migrateToLatest)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V46")
                .hasMessageContaining("position 7")
                .hasMessageContaining("has symbol");
    }

    @Test
    void legsNotSummingToQtyAbortsTheMigrationByName() throws Exception {
        migrateToV45();
        try (Connection conn = connect()) {
            insertPosition(conn, 7, "OFG", new BigDecimal("42"));
            // Wrong quantities on the expected stop-order ids -- sum (21+20=41) does not match
            // the position's qty (42), and does not match the broker-recorded 21+21 either.
            insertLeg(conn, 7, 1, "5039387855", new BigDecimal("21"));
            insertLeg(conn, 7, 2, "5039471907", new BigDecimal("20"));

            insertPosition(conn, 12, "RGNX", new BigDecimal("209"));
            insertLeg(conn, 12, 1, "5039591743", new BigDecimal("107"));
            insertLeg(conn, 12, 2, "5039676276", new BigDecimal("102"));
        }

        assertThatThrownBy(this::migrateToLatest)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V46")
                .hasMessageContaining("position 7")
                .hasMessageContaining("do not carry the broker-recorded quantities");
    }

    private void migrateToV45() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("45"))
                .load()
                .migrate();
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
}
