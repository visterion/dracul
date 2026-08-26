package de.visterion.dracul.executor;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates V45's SECOND abort path: a two-tranche position whose stop-order ids ARE the seeded
 * ones, but whose own {@code qty} does not equal the two seeded quantities added up.
 *
 * <p>{@link ExecutorPositionLegBackfillGuardIT} covers the first abort path (ids not in the seed
 * at all) and never reaches this one, because an unseeded id raises before the sum is ever
 * computed. Reaching this branch therefore requires a fixture built from the migration's own
 * {@code _leg_qty_seed} lines ({@link V45Seed}) — which is also what makes it a real check on the
 * live book: it is the guard that fires if a trim moved a position's qty between reading the
 * broker's per-leg quantities and running the migration, the exact deploy hazard V45's header
 * documents.
 *
 * <p>Own container: a failed migration leaves Flyway's schema history dirty, so this scenario
 * cannot share a database with a passing one.
 */
class ExecutorPositionLegBackfillSumGuardIT {

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
    void seededLegsThatDoNotSumToThePositionQtyAbortTheMigrationByPositionId() throws Exception {
        List<V45Seed.SeedLeg> seed = V45Seed.firstAsymmetricTwoTrancheGroup();
        V45Seed.SeedLeg leg1 = seed.get(0);
        V45Seed.SeedLeg leg2 = seed.get(1);
        // One share off the seeded sum — the smallest divergence a trim between the broker read
        // and the migration could produce, and the one a coarser check would let through.
        BigDecimal wrongTotal = leg1.qty().add(leg2.qty()).add(BigDecimal.ONE);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("44"))
                .load()
                .migrate();

        long positionId;
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            positionId = ExecutorPositionLegBackfillIT.insertPosition(conn, "ACME", wrongTotal,
                    "OPEN", "ord-1", leg1.stopOrderId(), "ord-2", leg2.stopOrderId());
        }

        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThatThrownBy(latest::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("leg backfill")
                .hasMessageContaining("position " + positionId)
                .hasMessageContaining("do not sum to its qty");
    }
}
