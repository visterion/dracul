package de.visterion.dracul.renfield;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshots the depot-holding state for every symbol under review at the moment a
 * renfield run is triggered (Spec 2026-08-08). The action-check (Task 7) reads this
 * snapshot instead of the live depot, so a `buy` proposal is judged against what was
 * actually held when the agent reasoned about it — not against a depot that may have
 * changed (or failed to load) by the time the webhook is evaluated ~90s later.
 */
@Repository
public class RenfieldRunContextRepository {

    private final JdbcClient jdbc;

    public RenfieldRunContextRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Persists one row per symbol. Idempotent: a re-trigger for the same run_id
     *  overwrites the prior snapshot for that symbol rather than erroring. No-op for
     *  an empty/null map — nothing to snapshot. */
    public void save(String runId, Map<String, Boolean> heldBySymbol, String positionSource) {
        if (heldBySymbol == null || heldBySymbol.isEmpty()) return;
        for (Map.Entry<String, Boolean> e : heldBySymbol.entrySet()) {
            jdbc.sql("""
                    INSERT INTO renfield_run_context (run_id, symbol, held, position_source)
                    VALUES (:runId, :symbol, :held, :source)
                    ON CONFLICT (run_id, symbol)
                    DO UPDATE SET held = EXCLUDED.held, position_source = EXCLUDED.position_source
                    """)
                    .param("runId", runId)
                    .param("symbol", e.getKey())
                    .param("held", e.getValue())
                    .param("source", positionSource)
                    .update();
        }
    }

    /** The holding snapshot for one symbol within one run, as taken at trigger time. */
    public Optional<RunContextRow> findBySymbol(String runId, String symbol) {
        return jdbc.sql("""
                SELECT symbol, held, position_source, created_at
                FROM renfield_run_context
                WHERE run_id = :runId AND symbol = :symbol
                """)
                .param("runId", runId)
                .param("symbol", symbol)
                .query(this::mapRow)
                .optional();
    }

    private RunContextRow mapRow(ResultSet rs, int n) throws SQLException {
        var createdAt = rs.getTimestamp("created_at");
        return new RunContextRow(
                rs.getString("symbol"),
                rs.getBoolean("held"),
                rs.getString("position_source"),
                createdAt == null ? null : createdAt.toInstant());
    }
}
