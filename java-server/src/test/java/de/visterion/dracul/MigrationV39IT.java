package de.visterion.dracul;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/** V39 adds prey.run_id and executor_signal.prey_id (both nullable, forward-only)
 *  to anchor executor signals to the prey they originated from. */
class MigrationV39IT {

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
    void v39AddsRunIdAndPreyIdColumns() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(columnExists(conn, "prey", "run_id")).isTrue();
            assertThat(columnExists(conn, "executor_signal", "prey_id")).isTrue();
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
