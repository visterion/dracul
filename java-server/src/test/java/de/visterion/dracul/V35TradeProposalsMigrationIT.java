package de.visterion.dracul;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** V35 applies on top of the full chain (incl. V34) and its UNIQUE (run_id, symbol)
 *  makes ON CONFLICT DO NOTHING inserts idempotent. */
class V35TradeProposalsMigrationIT {

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
    void migratesAndEnforcesRunSymbolUniqueness() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            int first = st.executeUpdate("""
                    INSERT INTO trade_proposals (id, owner, symbol, action, rationale, run_id)
                    VALUES (gen_random_uuid(), 'primary@x.com', 'ACME', 'buy', 'r', 'run-1')
                    ON CONFLICT (run_id, symbol) DO NOTHING
                    """);
            int second = st.executeUpdate("""
                    INSERT INTO trade_proposals (id, owner, symbol, action, rationale, run_id)
                    VALUES (gen_random_uuid(), 'primary@x.com', 'ACME', 'sell', 'r2', 'run-1')
                    ON CONFLICT (run_id, symbol) DO NOTHING
                    """);
            assertThat(first).isEqualTo(1);
            assertThat(second).isZero();

            var rs = st.executeQuery("SELECT COUNT(*) FROM trade_proposals");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
