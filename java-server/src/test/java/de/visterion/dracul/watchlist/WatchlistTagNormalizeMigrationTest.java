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
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates V37__watchlist_tag_normalize.sql: uppercases all pre-existing
 * watchlist_items.tag values, defensively coerces anything outside
 * {HELD,TRACKING} (including empty string) to 'TRACKING', then adds
 * CHECK (tag IN ('HELD','TRACKING')) as chk_watchlist_tag.
 *
 * Uses a standalone container + programmatic Flyway (not the Spring context),
 * mirroring WatchlistSourceMigrationTest / V32SeedMigrationTest.
 */
class WatchlistTagNormalizeMigrationTest {

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
    void normalizesTagCasingAndAddsCheckConstraint() throws Exception {
        // 1) Migrate to V36 (one version before the normalization under test) so the
        // V2 demo rows and V32 seed rows exist with lowercase tags, without the CHECK yet.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("36"))
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // 2) Insert extra pre-existing rows to cover the defensive cases: an empty
            // tag, and an unexpected non-{held,tracking} value — BEFORE V37 runs so the
            // normalization UPDATEs (not just the CHECK) are what's under test.
            UUID emptyTagId = UUID.randomUUID();
            UUID unexpectedTagId = UUID.randomUUID();
            insertWatchlistItem(conn, emptyTagId, "EMPTYTAG", "");
            insertWatchlistItem(conn, unexpectedTagId, "WATCHTAG", "watch");

            // 3) Migrate to latest (runs V37 — normalizes casing + adds the CHECK).
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            // 4a) V2 demo / V32 seed rows previously lowercase 'held'/'tracking' -> uppercase.
            assertThat(tagsInUse(conn)).allMatch(tag -> tag.equals("HELD") || tag.equals("TRACKING"));

            // 4b) empty tag '' -> defensively coerced to 'TRACKING'.
            assertThat(tagOf(conn, "EMPTYTAG")).isEqualTo("TRACKING");

            // 4c) unexpected value 'watch' -> defensively coerced to 'TRACKING'.
            assertThat(tagOf(conn, "WATCHTAG")).isEqualTo("TRACKING");

            // 4d) post-migration: CHECK rejects an invalid tag on INSERT.
            assertThatThrownBy(() -> insertWatchlistItem(conn, UUID.randomUUID(), "BADTAG", "foo"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_watchlist_tag");
        }
    }

    private static java.util.List<String> tagsInUse(Connection conn) throws Exception {
        java.util.List<String> tags = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT tag FROM watchlist_items");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tags.add(rs.getString("tag"));
            }
        }
        return tags;
    }

    private static String tagOf(Connection conn, String ticker) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tag FROM watchlist_items WHERE ticker = ? AND user_id = 'default'")) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row present for %s", ticker).isTrue();
                return rs.getString("tag");
            }
        }
    }

    private static void insertWatchlistItem(Connection conn, UUID id, String ticker, String tag)
            throws Exception {
        String sql = """
                INSERT INTO watchlist_items (id, ticker, company_name, current_price,
                                              day_change_percent, status, added_at, tag,
                                              user_id)
                VALUES (?, ?, ?, 100.0, 0.0, 'calm', CURRENT_DATE, ?, 'default')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, ticker);
            ps.setString(3, ticker + " Inc");
            ps.setString(4, tag);
            ps.executeUpdate();
        }
    }
}
