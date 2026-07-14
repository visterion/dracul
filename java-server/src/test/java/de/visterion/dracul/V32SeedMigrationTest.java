package de.visterion.dracul;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates V32__seed_global_watchlist.sql: the idempotent seed of ~20 non-US
 * (XETRA/Tokyo/Hong Kong) blue-chips for the global lazarus hunt. Migrates to V31,
 * seeds a pre-existing conflicting (default, SAP.DE) row plus an untouched US row,
 * then migrates to latest (runs V32) and asserts:
 *  - all seeded non-US rows exist with a non-null currency and all NOT-NULL columns set,
 *  - the pre-existing (default, SAP.DE) row is NOT overwritten (ON CONFLICT DO NOTHING),
 *  - the pre-existing US row is untouched.
 * Uses a standalone container + programmatic Flyway (not the Spring context).
 */
class V32SeedMigrationTest {

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
    void seedsNonUsBlueChipsIdempotentlyWithoutOverwriting() throws Exception {
        // 1) Migrate to V31 (one version before the seed under test).
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("31"))
                .load()
                .migrate();

        UUID preexistingSapId = UUID.randomUUID();
        UUID preexistingUsId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // 2) Seed a pre-existing (default, SAP.DE) row with a distinctive company name +
            // price so we can prove V32's ON CONFLICT DO NOTHING did NOT overwrite it, and a
            // pre-existing US row that must remain untouched.
            insertWatchlistItem(conn, preexistingSapId, "SAP.DE", "USER EDITED SAP", 999.99,
                    "alert", "held", "EUR", "default");
            insertWatchlistItem(conn, preexistingUsId, "AAPL", "Apple Inc", 210.00,
                    "calm", "tracking", "USD", "default");

            // 3) Migrate to latest (runs V32).
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            // 4a) Every seeded non-US ticker exists with a non-null currency + all NOT-NULL cols.
            String[][] expected = {
                    {"SAP.DE", "EUR"}, {"SIE.DE", "EUR"}, {"ALV.DE", "EUR"}, {"BAS.DE", "EUR"},
                    {"BAYN.DE", "EUR"}, {"DTE.DE", "EUR"}, {"MBG.DE", "EUR"}, {"BMW.DE", "EUR"},
                    {"MUV2.DE", "EUR"}, {"VOW3.DE", "EUR"},
                    {"7203.T", "JPY"}, {"6758.T", "JPY"}, {"9984.T", "JPY"}, {"8306.T", "JPY"},
                    {"6501.T", "JPY"},
                    {"0700.HK", "HKD"}, {"0941.HK", "HKD"}, {"1299.HK", "HKD"}, {"0005.HK", "HKD"}
            };
            for (String[] row : expected) {
                assertRowPresentWithCurrency(conn, row[0], row[1]);
            }

            // 4b) The pre-existing (default, SAP.DE) row was NOT overwritten by the seed.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT company_name, current_price, currency FROM watchlist_items "
                            + "WHERE user_id = 'default' AND ticker = 'SAP.DE'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("exactly one SAP.DE row").isTrue();
                    assertThat(rs.getString("company_name")).isEqualTo("USER EDITED SAP");
                    assertThat(rs.getDouble("current_price")).isEqualTo(999.99);
                    assertThat(rs.getString("currency")).isEqualTo("EUR");
                    assertThat(rs.next()).as("no duplicate SAP.DE row").isFalse();
                }
            }

            // 4c) The pre-existing US row is untouched.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT company_name, current_price FROM watchlist_items "
                            + "WHERE user_id = 'default' AND ticker = 'AAPL'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("company_name")).isEqualTo("Apple Inc");
                    assertThat(rs.getDouble("current_price")).isEqualTo(210.00);
                }
            }

            // 4d) Re-running V32 stays idempotent: no duplicate rows for a seeded ticker.
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            assertThat(countRows(conn, "SIE.DE")).as("SIE.DE seeded exactly once").isEqualTo(1);
        }
    }

    private static void assertRowPresentWithCurrency(Connection conn, String ticker, String currency)
            throws Exception {
        String sql = "SELECT company_name, current_price, day_change_percent, status, added_at, "
                + "tag, currency FROM watchlist_items WHERE user_id = 'default' AND ticker = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row present for %s", ticker).isTrue();
                assertThat(rs.getString("company_name")).as("company_name for %s", ticker).isNotBlank();
                assertThat(rs.getBigDecimal("current_price")).as("current_price for %s", ticker).isNotNull();
                assertThat(rs.getObject("day_change_percent")).as("day_change_percent for %s", ticker).isNotNull();
                assertThat(rs.getString("status")).as("status for %s", ticker).isNotBlank();
                assertThat(rs.getDate("added_at")).as("added_at for %s", ticker).isNotNull();
                assertThat(rs.getString("tag")).as("tag for %s", ticker).isNotBlank();
                assertThat(rs.getString("currency")).as("currency for %s", ticker).isEqualTo(currency);
            }
        }
    }

    private static int countRows(Connection conn, String ticker) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM watchlist_items WHERE user_id = 'default' AND ticker = ?")) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void insertWatchlistItem(Connection conn, UUID id, String ticker,
                                            String companyName, double price, String status,
                                            String tag, String currency, String userId) throws Exception {
        String sql = """
                INSERT INTO watchlist_items (id, ticker, company_name, current_price,
                                              day_change_percent, status, added_at, tag, currency, user_id)
                VALUES (?, ?, ?, ?, 0.0, ?, CURRENT_DATE, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, ticker);
            ps.setString(3, companyName);
            ps.setDouble(4, price);
            ps.setString(5, status);
            ps.setString(6, tag);
            ps.setString(7, currency);
            ps.setString(8, userId);
            ps.executeUpdate();
        }
    }
}
