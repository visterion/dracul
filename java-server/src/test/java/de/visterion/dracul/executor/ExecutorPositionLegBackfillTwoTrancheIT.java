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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates V45__executor_position_leg.sql's SECOND backfill INSERT — the one that writes the
 * tranche-2 legs of the four two-tranche positions, and the only code in this branch that touches
 * them.
 *
 * <p>{@link ExecutorPositionLegBackfillIT} seeds a single-tranche row and
 * {@link ExecutorPositionLegBackfillGuardIT} builds a two-tranche row that aborts BEFORE the
 * INSERTs run, so between them the two-tranche INSERT could be deleted outright and both would
 * stay green. This test closes that hole: the fixture's stop-order ids come from V45's own
 * {@code _leg_qty_seed} VALUES lines via {@link V45Seed} (the same technique {@link V46Facts} uses
 * for V46), so the REAL INSERT is exercised against the REAL seed while the ids stay written down
 * in exactly one file. Only the ids and quantities are read from the seed — the fixture's symbol
 * is synthetic, because the migration matches on stop-order id alone and never on symbol.
 *
 * <p>A single-tranche control position is migrated alongside it and asserted untouched by the
 * two-tranche INSERT: a second INSERT that lost its {@code WHERE tranche2_order_id IS NOT NULL}
 * would otherwise write a bogus tranche-2 leg for every row in the book and still pass.
 */
class ExecutorPositionLegBackfillTwoTrancheIT {

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
    void twoTrancheBackfillWritesTheSeededPerLegQuantitiesAndLeavesSingleTrancheRowsAlone()
            throws Exception {
        // Deliberately an ASYMMETRIC group: an even split is what a wrong halving of the row's qty
        // would also produce, so only an uneven one can tell the per-leg seed from the defect it
        // replaced. V45Seed fails loudly rather than silently picking an even split.
        List<V45Seed.SeedLeg> seed = V45Seed.firstAsymmetricTwoTrancheGroup();
        V45Seed.SeedLeg leg1 = seed.get(0);
        V45Seed.SeedLeg leg2 = seed.get(1);
        BigDecimal total = leg1.qty().add(leg2.qty());

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("44"))
                .load()
                .migrate();

        long twoTrancheId;
        long controlId;
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            twoTrancheId = ExecutorPositionLegBackfillIT.insertPosition(conn, "ACME", total, "OPEN",
                    "ord-1", leg1.stopOrderId(), "ord-2", leg2.stopOrderId());
            controlId = ExecutorPositionLegBackfillIT.insertPosition(conn, "EXMPL",
                    new BigDecimal("13"), "OPEN", "ord-3", "stop-3", null, null);

            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertLeg(conn, twoTrancheId, 1, "ord-1", leg1.stopOrderId(), leg1.qty());
            assertLeg(conn, twoTrancheId, 2, "ord-2", leg2.stopOrderId(), leg2.qty());
            assertThat(legCount(conn, twoTrancheId)).as("exactly two legs").isEqualTo(2);

            assertLeg(conn, controlId, 1, "ord-3", "stop-3", new BigDecimal("13"));
            assertThat(legCount(conn, controlId))
                    .as("the single-tranche control must not gain a tranche-2 leg").isEqualTo(1);
        }
    }

    private void assertLeg(Connection conn, long positionId, int tranche, String entryOrderId,
            String stopOrderId, BigDecimal qty) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT entry_order_id, stop_order_id, qty, status
                FROM executor_position_leg WHERE position_id = ? AND tranche = ?
                """)) {
            ps.setLong(1, positionId);
            ps.setInt(2, tranche);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("leg %d of position %d exists", tranche, positionId).isTrue();
                assertThat(rs.getString("entry_order_id")).isEqualTo(entryOrderId);
                assertThat(rs.getString("stop_order_id")).isEqualTo(stopOrderId);
                assertThat(rs.getBigDecimal("qty")).isEqualByComparingTo(qty);
                assertThat(rs.getString("status")).isEqualTo("OPEN");
            }
        }
    }

    private int legCount(Connection conn, long positionId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM executor_position_leg WHERE position_id = ?")) {
            ps.setLong(1, positionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
