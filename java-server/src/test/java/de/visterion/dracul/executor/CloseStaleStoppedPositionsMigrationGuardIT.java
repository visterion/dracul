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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates V46__close_stale_stopped_positions.sql's fail-loud guards against the REAL migration
 * (not a copy of its SQL): if a position id that already exists in the book does not match the
 * broker record V46 is keyed on -- wrong symbol, legs that don't carry the exact recorded stop
 * quantities, or legs that don't sum to the position's own qty -- it must abort by name rather
 * than write something approximate. (A position id that is entirely absent is a deliberate no-op,
 * not a guard case here -- every fresh Testcontainers/CI schema starts without ids 7/12, so
 * requiring them to exist would make this migration untestable outside production; see V46's own
 * header comment.) Each scenario gets its own container: a failed migration leaves Flyway's schema
 * history dirty, mirroring ExecutorPositionLegBackfillGuardIT.
 *
 * <p>Real stop-order ids and quantities used to construct the "almost right" rows below come from
 * {@link V46Facts}, parsed off V46's own comment lines -- not retyped here.
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
        V46Facts.Position ofg = V46Facts.positions().get(7L);
        // Position 7 exists but under a different symbol than the broker record names -- must
        // never be touched silently just because the id matches.
        try (Connection conn = connect()) {
            insertPosition(conn, ofg.id(), "NOTOFG", ofg.qty());
        }

        assertThatThrownBy(this::migrateToLatest)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V46")
                .hasMessageContaining("position 7")
                .hasMessageContaining("has symbol");
    }

    @Test
    void legQuantityMismatchAbortsTheMigrationByName() throws Exception {
        migrateToV45();
        V46Facts.Position ofg = V46Facts.positions().get(7L);
        List<V46Facts.Leg> ofgLegs = V46Facts.legsFor(7L);
        // Both legs sit on the broker-recorded stop-order ids, but the second one carries the
        // wrong quantity for that leg -- the exact per-leg check must catch this before the sum
        // check ever runs (21+20=41 happens to not equal qty either, but that's not what this
        // test is exercising).
        try (Connection conn = connect()) {
            insertPosition(conn, ofg.id(), ofg.symbol(), ofg.qty());
            insertLeg(conn, ofgLegs.get(0).positionId(), ofgLegs.get(0).tranche(),
                    ofgLegs.get(0).stopOrderId(), ofgLegs.get(0).qty());
            insertLeg(conn, ofgLegs.get(1).positionId(), ofgLegs.get(1).tranche(),
                    ofgLegs.get(1).stopOrderId(), ofgLegs.get(1).qty().subtract(BigDecimal.ONE));
        }

        assertThatThrownBy(this::migrateToLatest)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V46")
                .hasMessageContaining("position 7")
                .hasMessageContaining("do not carry the broker-recorded quantities");
    }

    @Test
    void legsNotSummingToPositionQtyAbortsTheMigrationByName() throws Exception {
        migrateToV45();
        V46Facts.Position ofg = V46Facts.positions().get(7L);
        List<V46Facts.Leg> ofgLegs = V46Facts.legsFor(7L);
        // Both legs carry exactly the broker-recorded stop-order id and quantity (passing the
        // per-leg exact-quantity check), but the position's own qty is wrong -- one less than the
        // legs' real sum -- so only the sum check can catch this.
        try (Connection conn = connect()) {
            insertPosition(conn, ofg.id(), ofg.symbol(), ofg.qty().subtract(BigDecimal.ONE));
            for (V46Facts.Leg leg : ofgLegs) {
                insertLeg(conn, leg.positionId(), leg.tranche(), leg.stopOrderId(), leg.qty());
            }
        }

        assertThatThrownBy(this::migrateToLatest)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V46")
                .hasMessageContaining("position 7")
                .hasMessageContaining("open legs sum to");
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
