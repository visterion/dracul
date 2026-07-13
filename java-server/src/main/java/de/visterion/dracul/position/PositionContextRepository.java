package de.visterion.dracul.position;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code position_context} (V28): the research context (verdict, kill
 * criteria, horizon, thesis snapshot, stops) attached to a held paper-depot position.
 * JdbcClient-based, mirroring {@link de.visterion.dracul.strigoi.spin.SpinCandidateRepository}:
 * explicit {@code INSERT ... ON CONFLICT DO NOTHING} for idempotent opens, guarded by the
 * partial unique index {@code uq_position_context_open} on {@code (connection, lower(symbol))
 * WHERE closed_at IS NULL} -- exactly one OPEN row per (connection, symbol), degrading
 * gracefully to a fresh row once the prior one is closed.
 */
@Repository
public class PositionContextRepository {

    private static final Logger log = LoggerFactory.getLogger(PositionContextRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PositionContextRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Idempotent open: inserts a new OPEN {@code position_context} row for
     * (connection, lower(symbol)) unless one already exists, in which case the existing
     * row's id is returned unchanged (ON CONFLICT DO NOTHING against the partial unique
     * index {@code uq_position_context_open}, then a re-select). A symbol whose prior
     * position was closed ({@code closed_at} set) is unconstrained by the partial index and
     * gets a brand-new row -- see {@link #markClosed}.
     */
    public String upsertOnOpen(String connection, String symbol, String verdictId,
                                JsonNode killCriteria, String horizon, JsonNode thesisSnapshot,
                                BigDecimal initialStop, String source) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO position_context
                  (id, connection, symbol, verdict_id, kill_criteria, horizon, thesis_snapshot,
                   initial_stop, source)
                VALUES
                  (:id, :connection, :symbol, :verdictId, CAST(:killCriteria AS jsonb), :horizon,
                   CAST(:thesisSnapshot AS jsonb), :initialStop, :source)
                ON CONFLICT (connection, lower(symbol)) WHERE closed_at IS NULL DO NOTHING
                """)
                .param("id", id)
                .param("connection", connection)
                .param("symbol", symbol)
                .param("verdictId", verdictId)
                .param("killCriteria", writeJson(killCriteria))
                .param("horizon", horizon)
                .param("thesisSnapshot", writeJson(thesisSnapshot))
                .param("initialStop", initialStop)
                .param("source", source)
                .update();

        return findOpenBySymbol(connection, symbol)
                .map(PositionContextRow::id)
                .orElse(id);
    }

    /** The currently OPEN row for (connection, lower(symbol)), if any. */
    public Optional<PositionContextRow> findOpenBySymbol(String connection, String symbol) {
        return jdbc.sql("""
                SELECT id, connection, symbol, verdict_id, kill_criteria, horizon,
                       thesis_snapshot, initial_stop, active_stop, opened_at, closed_at, source
                  FROM position_context
                 WHERE connection = :connection AND lower(symbol) = lower(:symbol)
                   AND closed_at IS NULL
                """)
                .param("connection", connection)
                .param("symbol", symbol)
                .query(this::mapRow)
                .optional();
    }

    /** All OPEN rows for a connection -- the paper depot's live position-context set. */
    public List<PositionContextRow> findAllOpen(String connection) {
        return jdbc.sql("""
                SELECT id, connection, symbol, verdict_id, kill_criteria, horizon,
                       thesis_snapshot, initial_stop, active_stop, opened_at, closed_at, source
                  FROM position_context
                 WHERE connection = :connection AND closed_at IS NULL
                """)
                .param("connection", connection)
                .query(this::mapRow)
                .list();
    }

    /** Heals a partially-null OPEN row (e.g. a reconciler "none" shadow, or an early executor
     *  write) with real values, COALESCE-keeping anything already set. The trailing NULL-guard
     *  makes it a true no-op once all four fields are populated (no per-pass WAL churn). */
    public void updateContextIfNull(String connection, String symbol, JsonNode thesisSnapshot,
                                    JsonNode killCriteria, String horizon, BigDecimal initialStop) {
        jdbc.sql("""
                UPDATE position_context
                   SET thesis_snapshot = COALESCE(thesis_snapshot, CAST(:thesisSnapshot AS jsonb)),
                       kill_criteria   = COALESCE(kill_criteria,   CAST(:killCriteria AS jsonb)),
                       horizon         = COALESCE(horizon, :horizon),
                       initial_stop    = COALESCE(initial_stop, :initialStop)
                 WHERE connection = :connection AND lower(symbol) = lower(:symbol)
                   AND closed_at IS NULL
                   AND (thesis_snapshot IS NULL OR kill_criteria IS NULL
                        OR horizon IS NULL OR initial_stop IS NULL)
                """)
                .param("thesisSnapshot", writeJson(thesisSnapshot))
                .param("killCriteria", writeJson(killCriteria))
                .param("horizon", horizon)
                .param("initialStop", initialStop)
                .param("connection", connection)
                .param("symbol", symbol)
                .update();
    }

    /** Upgrades an OPEN, still-unlinked (verdict_id IS NULL) row with a verdict link, COALESCE-
     *  filling thesis/kill/horizon so a "none" shadow later linked to a verdict gets the same
     *  fields the direct verdict-backfill would have set. Never overwrites an existing link or a
     *  Prey thesis already present. */
    public void updateVerdictLink(String connection, String symbol, String verdictId,
                                  JsonNode thesisSnapshot, JsonNode killCriteria, String horizon) {
        jdbc.sql("""
                UPDATE position_context
                   SET verdict_id      = :verdictId,
                       thesis_snapshot = COALESCE(thesis_snapshot, CAST(:thesisSnapshot AS jsonb)),
                       kill_criteria   = COALESCE(kill_criteria,   CAST(:killCriteria AS jsonb)),
                       horizon         = COALESCE(horizon, :horizon)
                 WHERE connection = :connection AND lower(symbol) = lower(:symbol)
                   AND closed_at IS NULL AND verdict_id IS NULL
                """)
                .param("verdictId", verdictId)
                .param("thesisSnapshot", writeJson(thesisSnapshot))
                .param("killCriteria", writeJson(killCriteria))
                .param("horizon", horizon)
                .param("connection", connection)
                .param("symbol", symbol)
                .update();
    }

    /** Overwrites both stops on the row (the trailing stop moves -- not freeze-once). */
    public void updateStops(String id, BigDecimal initialStop, BigDecimal activeStop) {
        jdbc.sql("""
                UPDATE position_context
                   SET initial_stop = :initialStop, active_stop = :activeStop
                 WHERE id = :id
                """)
                .param("initialStop", initialStop)
                .param("activeStop", activeStop)
                .param("id", id)
                .update();
    }

    /** Closes the position context, stamping {@code closed_at = now()}. */
    public void markClosed(String id) {
        jdbc.sql("UPDATE position_context SET closed_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    private PositionContextRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PositionContextRow(
                rs.getString("id"),
                rs.getString("connection"),
                rs.getString("symbol"),
                rs.getString("verdict_id"),
                readJson(rs.getString("kill_criteria")),
                rs.getString("horizon"),
                readJson(rs.getString("thesis_snapshot")),
                rs.getBigDecimal("initial_stop"),
                rs.getBigDecimal("active_stop"),
                rs.getString("opened_at"),
                rs.getString("closed_at"),
                rs.getString("source"));
    }

    private JsonNode readJson(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize position_context JSON: {}", json, e);
            return null;
        }
    }

    private String writeJson(JsonNode node) {
        if (node == null) return null;
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize position_context JSON", e);
        }
    }
}
