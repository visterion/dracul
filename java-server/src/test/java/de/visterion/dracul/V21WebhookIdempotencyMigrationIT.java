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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the V21 dedup/remap DML (V21__webhook_idempotency.sql) against dirty
 * (pre-V21) data. Uses a standalone container + programmatic Flyway (not the Spring
 * context) so we can migrate to V20, seed conflicting rows, then migrate to latest
 * and assert the one-shot remap behaved correctly.
 */
class V21WebhookIdempotencyMigrationIT {

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
    void dedupesRemapsVerdictsAndCreatesIndexesAgainstDirtyData() throws Exception {
        // 1) Migrate to V20 (one version before the dedup migration under test).
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("20"))
                .load()
                .migrate();

        UUID keeperId = UUID.randomUUID();
        UUID loser1Id = UUID.randomUUID();
        UUID loser2Id = UUID.randomUUID();
        UUID unrelatedId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // 2) Seed dirty pre-V21 data: 3 prey rows sharing one natural key on the same
            // UTC day (keeper = earliest discovered_at), plus 1 unrelated prey row.
            insertPrey(conn, keeperId, "ACME", "SPIN_OFF", "strigoi-spin", "default",
                    "2026-07-10T06:00:00Z"); // earliest -> keeper
            insertPrey(conn, loser1Id, "ACME", "SPIN_OFF", "strigoi-spin", "default",
                    "2026-07-10T09:00:00Z"); // later same day -> loser
            insertPrey(conn, loser2Id, "ACME", "SPIN_OFF", "strigoi-spin", "default",
                    "2026-07-10T15:00:00Z"); // later same day -> loser
            insertPrey(conn, unrelatedId, "BETA", "INSIDER_CLUSTER", "strigoi-insider", "default",
                    "2026-07-08T10:00:00Z"); // unrelated natural key

            // 3) Seed 2 verdicts referencing the dirty prey rows.
            UUID verdict1Id = UUID.randomUUID();
            UUID verdict2Id = UUID.randomUUID();
            insertVerdict(conn, verdict1Id, "ACME",
                    "[\"" + loser1Id + "\", \"" + unrelatedId + "\"]");
            insertVerdict(conn, verdict2Id, "ACME",
                    "[\"" + loser2Id + "\", \"" + keeperId + "\"]");

            // 4) Migrate to latest (runs V21).
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            // 5a) Of the 4 seeded prey rows, only the keeper + unrelated prey remain
            // (the two losers sharing the keeper's natural key were deleted).
            Set<UUID> seededIds = Set.of(keeperId, loser1Id, loser2Id, unrelatedId);
            Set<UUID> remainingSeededIds = new java.util.HashSet<>(queryPreyIds(conn));
            remainingSeededIds.retainAll(seededIds);
            assertThat(remainingSeededIds).containsExactlyInAnyOrder(keeperId, unrelatedId);

            // 5b) verdict1's array = {keeper, unrelated} (loser remapped to keeper).
            List<UUID> verdict1Ids = queryContributingPreyIds(conn, verdict1Id);
            assertThat(verdict1Ids).containsExactlyInAnyOrder(keeperId, unrelatedId);

            // 5c) verdict2's array = {keeper} only, no duplicate keeper entry
            // (loser2 remaps to keeper, which was already directly referenced).
            List<UUID> verdict2Ids = queryContributingPreyIds(conn, verdict2Id);
            assertThat(verdict2Ids).containsExactly(keeperId);

            // 5d) Both unique indexes exist.
            assertThat(indexExists(conn, "uq_prey_natural_day")).isTrue();
            assertThat(indexExists(conn, "uq_exit_signals_run_item")).isTrue();
        }
    }

    private static void insertPrey(Connection conn, UUID id, String symbol, String anomalyType,
                                    String discoveredBy, String userId, String discoveredAt) throws Exception {
        String sql = """
                INSERT INTO prey (id, symbol, company_name, anomaly_type, confidence, thesis,
                                   signals, risks, horizon, discovered_by, discovered_at, user_id)
                VALUES (?, ?, ?, ?, 0.7, 'dummy thesis', '[]'::jsonb, '[]'::jsonb, '6m', ?, ?::timestamptz, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, symbol);
            ps.setString(3, symbol + " Corp");
            ps.setString(4, anomalyType);
            ps.setString(5, discoveredBy);
            ps.setString(6, discoveredAt);
            ps.setString(7, userId);
            ps.executeUpdate();
        }
    }

    private static void insertVerdict(Connection conn, UUID id, String symbol,
                                       String contributingPreyIdsJson) throws Exception {
        String sql = """
                INSERT INTO verdicts (id, symbol, company_name, contributing_strigoi, consensus_score,
                                       summary, created_at, contributing_prey_ids)
                VALUES (?, ?, ?, '[]'::jsonb, 0.7, 'dummy summary', now(), ?::jsonb)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, symbol);
            ps.setString(3, symbol + " Corp");
            ps.setString(4, contributingPreyIdsJson);
            ps.executeUpdate();
        }
    }

    private static Set<UUID> queryPreyIds(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM prey")) {
            Set<UUID> ids = new java.util.HashSet<>();
            while (rs.next()) {
                ids.add((UUID) rs.getObject("id"));
            }
            return ids;
        }
    }

    private static List<UUID> queryContributingPreyIds(Connection conn, UUID verdictId) throws Exception {
        String sql = "SELECT contributing_prey_ids FROM verdicts WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, verdictId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String json = rs.getString(1);
                List<UUID> ids = new ArrayList<>();
                for (String raw : json.replace("[", "").replace("]", "").split(",")) {
                    String trimmed = raw.trim().replace("\"", "");
                    if (!trimmed.isEmpty()) {
                        ids.add(UUID.fromString(trimmed));
                    }
                }
                return ids;
            }
        }
    }

    private static boolean indexExists(Connection conn, String indexName) throws Exception {
        String sql = "SELECT 1 FROM pg_indexes WHERE indexname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
