package de.visterion.dracul.watchlist;

import org.flywaydb.core.Flyway;
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
 * Validates V36__watchlist_source.sql: adds watchlist_items.source (NOT NULL,
 * DEFAULT 'manual') and backfills provenance on a migrations-fresh DB (which
 * includes the V2 demo rows and the V32 non-US seed rows):
 *  - verdict_id != null                              -> 'verdict' (verdict beats seed)
 *  - V32 seed ticker, added_at = 2026-07-14, no verdict -> 'seed'
 *  - a row with the seed date but NOT a seed ticker    -> 'manual'
 *  - V2 demo 'AVGO' (has verdict_id)                   -> 'verdict'
 *  - V2 demo 'NVDA' (no verdict_id)                    -> 'manual'
 *  - any other non-seed row                            -> 'manual'
 * Uses a standalone container + programmatic Flyway (not the Spring context),
 * mirroring V32SeedMigrationTest.
 */
class WatchlistSourceMigrationTest {

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
    void backfillsProvenanceOnFreshMigratedDatabase() throws Exception {
        // 1) Migrate to V35 (one version before the backfill under test) so the V2 demo
        // rows and V32 seed rows exist without a `source` column yet.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("35"))
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // 2) Insert extra pre-existing rows to cover the ambiguous cases the fixed
            // demo/seed data doesn't exercise: a seed-date row with a non-seed ticker
            // (must stay 'manual'), and a plain manual row — both BEFORE V36 runs so the
            // backfill UPDATE (not just the column DEFAULT) is what's under test.
            UUID sameDateNonSeedId = UUID.randomUUID();
            UUID plainManualId = UUID.randomUUID();
            insertWatchlistItem(conn, sameDateNonSeedId, "TSLA", null, "2026-07-14");
            insertWatchlistItem(conn, plainManualId, "GOOG", null, "2026-01-01");

            // 3) Migrate to latest (runs V36 — adds `source` + backfills it).
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            // 4a) verdict_id != null -> 'verdict' (V2 demo AVGO has a verdict_id).
            assertThat(sourceOf(conn, "AVGO")).isEqualTo("verdict");

            // 4b) V32 seed ticker, added_at 2026-07-14, no verdict -> 'seed'.
            assertThat(sourceOf(conn, "SAP.DE")).isEqualTo("seed");
            assertThat(sourceOf(conn, "0005.HK")).isEqualTo("seed");

            // 4c) same seed date but ticker not in the seed list -> 'manual'.
            assertThat(sourceOf(conn, "TSLA")).isEqualTo("manual");

            // 4d) V2 demo NVDA (no verdict_id) -> 'manual'.
            assertThat(sourceOf(conn, "NVDA")).isEqualTo("manual");

            // 4e) any other non-seed row -> 'manual'.
            assertThat(sourceOf(conn, "GOOG")).isEqualTo("manual");

            // 4f) column is NOT NULL with default 'manual'.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT is_nullable, column_default FROM information_schema.columns "
                            + "WHERE table_name = 'watchlist_items' AND column_name = 'source'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                    assertThat(rs.getString("column_default")).contains("manual");
                }
            }
        }
    }

    private static String sourceOf(Connection conn, String ticker) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source FROM watchlist_items WHERE ticker = ? AND user_id = 'default'")) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row present for %s", ticker).isTrue();
                return rs.getString("source");
            }
        }
    }

    private static void insertWatchlistItem(Connection conn, UUID id, String ticker,
                                             UUID verdictId, String addedAt) throws Exception {
        String sql = """
                INSERT INTO watchlist_items (id, ticker, company_name, current_price,
                                              day_change_percent, status, added_at, tag,
                                              verdict_id, user_id)
                VALUES (?, ?, ?, 100.0, 0.0, 'calm', ?, 'tracking', ?, 'default')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, ticker);
            ps.setString(3, ticker + " Inc");
            ps.setObject(4, java.sql.Date.valueOf(addedAt));
            ps.setObject(5, verdictId);
            ps.executeUpdate();
        }
    }
}
