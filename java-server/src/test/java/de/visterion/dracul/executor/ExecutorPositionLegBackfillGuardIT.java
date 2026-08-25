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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the fail-loud guard in V45__executor_position_leg.sql: a two-tranche position whose
 * stop-order ids are not the ones seeded in the migration must abort the migration by name,
 * never fall back to a derived or halved quantity. Runs the REAL migration (not a copy of its
 * SQL) against a synthetic two-tranche row that cannot match the real seed, mirroring
 * ExecutorPositionLegBackfillIT's setup. Uses its own container: a failed migration leaves
 * Flyway's schema history dirty, so this scenario cannot share a database with a passing one.
 */
class ExecutorPositionLegBackfillGuardIT {

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
    void twoTranchePositionNotInTheSeedAbortsTheMigrationByPositionId() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("44"))
                .load()
                .migrate();

        long positionId;
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // Synthetic two-tranche position whose stop-order ids do not appear in V45's seed --
            // the seed only recognizes four specific real broker order ids, so any synthetic id
            // guarantees a miss here.
            positionId = ExecutorPositionLegBackfillIT.insertPosition(conn, "ACME", new BigDecimal("20"),
                    "OPEN", "ord-1", "stop-1", "ord-2", "stop-2");
        }

        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThatThrownBy(latest::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("leg backfill")
                .hasMessageContaining("position " + positionId)
                .hasMessageContaining("no seeded quantity");
    }
}
