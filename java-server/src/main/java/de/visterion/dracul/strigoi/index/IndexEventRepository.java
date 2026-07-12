package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.hunting.agora.IndexChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for tracked index-reconstitution events (V27 {@code index_event}).
 * JdbcClient-based, mirroring {@link de.visterion.dracul.strigoi.spin.SpinCandidateRepository}:
 * explicit {@code INSERT ... ON CONFLICT DO NOTHING} for idempotent ingestion, guarded
 * compare-and-set UPDATEs for the forward-only lifecycle, and no Spring Data JPA.
 *
 * <p>Idempotency mirrors the V27 expression unique index
 * {@code (index_name, upper(symbol), action, effective_date)}: one row per announced
 * constituent change. Symbols are stored upper-cased by the ingestion facade, so the
 * natural key and the per-symbol promotion lookup agree on identity.
 */
@Repository
public class IndexEventRepository {

    private static final Logger log = LoggerFactory.getLogger(IndexEventRepository.class);

    /** Terminal states derived from the enum, so the "non-terminal" scan stays in sync. */
    private static final List<String> TERMINAL_STATUSES = Arrays.stream(IndexEventStatus.values())
            .filter(IndexEventStatus::isTerminal).map(Enum::name).toList();

    private static final String COLS = """
            id, symbol, company_name, index_name, action, source,
            announcement_date, effective_date, status,
            announced_snapshot, post_snapshot,
            promoted_at, promoted_prey_id, discovered_at, last_checked_at,
            effective_at, closed_at, abandoned_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public IndexEventRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Ingests a screened change as an ANNOUNCED row. Idempotent: a row that collides on
     * the natural key ({@code (index_name, upper(symbol), action, effective_date)}) is
     * skipped via ON CONFLICT DO NOTHING, so re-running the hunt never duplicates a change
     * nor resets its lifecycle. {@code company_name} is normalised to NULL when blank (the
     * Agora tool does not carry it). Returns whether a new row was actually inserted.
     */
    public boolean upsertAnnounced(IndexChangeEvent e) {
        int rows = jdbc.sql("""
                INSERT INTO index_event
                  (symbol, company_name, index_name, action, source,
                   announcement_date, effective_date, status)
                VALUES
                  (:symbol, :companyName, :indexName, :action, :source,
                   :announcementDate::date, :effectiveDate::date, 'ANNOUNCED')
                ON CONFLICT (index_name, (upper(symbol)), action, effective_date) DO NOTHING
                """)
                .param("symbol", e.symbol())
                .param("companyName", emptyToNull(e.companyName()))
                .param("indexName", e.index())
                .param("action", e.action())
                .param("source", e.source())
                .param("announcementDate", e.announcementDate() == null ? null : e.announcementDate().toString())
                .param("effectiveDate", e.effectiveDate() == null ? null : e.effectiveDate().toString())
                .update();
        return rows > 0;
    }

    /**
     * Guarded forward-only status transition (compare-and-set on the current status).
     * Bumps {@code last_checked_at}, and stamps the transition's audit timestamp
     * ({@code effective_at}/{@code closed_at}/{@code abandoned_at}) where the target stage
     * has one (POST has none — it is an observation window, not a stamped instant). Never
     * reverses: the {@code WHERE status = from} guard makes concurrent/duplicate
     * reconciliations no-ops. Returns whether the row moved.
     */
    public boolean advanceStatus(long id, IndexEventStatus from, IndexEventStatus to) {
        String tsSet = switch (to) {
            case EFFECTIVE -> ", effective_at = now()";
            case CLOSED -> ", closed_at = now()";
            case ABANDONED -> ", abandoned_at = now()";
            default -> "";
        };
        int rows = jdbc.sql(
                "UPDATE index_event SET status = :to, last_checked_at = now()" + tsSet
                        + " WHERE id = :id AND status = :from")
                .param("to", to.name())
                .param("from", from.name())
                .param("id", id)
                .update();
        return rows > 0;
    }

    /**
     * Stores a per-stage enrichment snapshot as JSONB and bumps {@code last_checked_at}.
     * The column is chosen from a whitelisted switch on {@code stage} (never from
     * caller-supplied text), so there is no injection surface. Only ANNOUNCED and POST have
     * snapshot columns; EFFECTIVE is a transient tick. The snapshot is serialised with the
     * shared {@link ObjectMapper}. A null snapshot clears the column. Returns whether the
     * row exists.
     */
    public boolean storeSnapshot(long id, IndexEventStatus stage, JsonNode snapshot) {
        String column = switch (stage) {
            case ANNOUNCED -> "announced_snapshot";
            case POST -> "post_snapshot";
            default -> throw new IllegalArgumentException("no snapshot column for stage " + stage);
        };
        String json;
        try {
            json = snapshot == null ? null : mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize index_event snapshot", e);
        }
        int rows = jdbc.sql(
                "UPDATE index_event SET " + column + " = :json::jsonb, last_checked_at = now()"
                        + " WHERE id = :id")
                .param("json", json)
                .param("id", id)
                .update();
        return rows > 0;
    }

    /**
     * Active, not-yet-promoted rows for the LLM response payload: the tracked window
     * {ANNOUNCED, EFFECTIVE, POST} with {@code promoted_at IS NULL}. Terminal rows
     * (CLOSED/ABANDONED) and already-promoted rows are excluded — the hunter only reasons
     * over events still worth a fresh look. Newest-discovered first so the freshest setups
     * lead.
     */
    public List<IndexEventRow> findActiveUnpromoted(int limit) {
        return jdbc.sql("SELECT " + COLS + """
                FROM index_event
                WHERE status IN ('ANNOUNCED', 'EFFECTIVE', 'POST') AND promoted_at IS NULL
                ORDER BY discovered_at DESC
                LIMIT :limit
                """)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    /**
     * Non-terminal rows (everything but CLOSED/ABANDONED), oldest-checked first — the
     * reconciler's work queue. The ORDER BY last_checked_at ASC is served by
     * {@code idx_index_event_last_checked}; the terminal-status filter is a residual.
     */
    public List<IndexEventRow> findNonTerminalOldestCheckedFirst(int limit) {
        return jdbc.sql("SELECT " + COLS + """
                FROM index_event
                WHERE status NOT IN (:terminal)
                ORDER BY last_checked_at ASC
                LIMIT :limit
                """)
                .param("terminal", TERMINAL_STATUSES)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    /**
     * The ANNOUNCED, not-yet-promoted row carrying this symbol — the promotion lookup used
     * by {@link StrigoiIndexWebhookController#afterPersist} (later slice) to match an emitted
     * prey back to its tracked event. Restricted to {@code status = 'ANNOUNCED' AND
     * promoted_at IS NULL} so a prey can only ever promote an event still in the window-open
     * state and not already promoted; a prey whose symbol matches no such row yields empty
     * and is skipped fail-soft. Matched on {@code upper(symbol)} (the natural-key casing).
     * Newest-discovered first for determinism on the rare symbol collision. Blank symbol
     * yields empty.
     */
    public Optional<IndexEventRow> findPromotableBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return jdbc.sql("SELECT " + COLS + """
                FROM index_event
                WHERE status = 'ANNOUNCED' AND promoted_at IS NULL AND upper(symbol) = upper(:symbol)
                ORDER BY discovered_at DESC
                LIMIT 1
                """)
                .param("symbol", symbol)
                .query(this::mapRow)
                .optional();
    }

    /**
     * Guarded promotion stamp: sets {@code promoted_at = now()} and {@code promoted_prey_id}
     * only while the row is still unpromoted ({@code promoted_at IS NULL}). The CAS guard
     * makes this idempotent — a retried webhook delivery, or a second prey matching the same
     * event, is a no-op and never re-stamps a different prey id. Once stamped, the row drops
     * out of {@link #findActiveUnpromoted} and {@link #findPromotableBySymbol}. Returns
     * whether the row moved this call.
     */
    public boolean markPromoted(long id, String preyId) {
        int rows = jdbc.sql("""
                UPDATE index_event
                   SET promoted_at = now(), promoted_prey_id = :preyId, last_checked_at = now()
                 WHERE id = :id AND promoted_at IS NULL
                """)
                .param("preyId", preyId)
                .param("id", id)
                .update();
        return rows > 0;
    }

    /** Bumps {@code last_checked_at} without any state change (checked, nothing moved). */
    public boolean touchLastChecked(long id) {
        return jdbc.sql("UPDATE index_event SET last_checked_at = now() WHERE id = :id")
                .param("id", id)
                .update() > 0;
    }

    /** Single row by id (test/verification helper; also useful for the promotion path). */
    public Optional<IndexEventRow> findById(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM index_event WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    private IndexEventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IndexEventRow(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("company_name"),
                rs.getString("index_name"),
                rs.getString("action"),
                rs.getString("source"),
                rs.getObject("announcement_date", LocalDate.class),
                rs.getObject("effective_date", LocalDate.class),
                IndexEventStatus.valueOf(rs.getString("status")),
                readJson(rs.getString("announced_snapshot")),
                readJson(rs.getString("post_snapshot")),
                rs.getString("promoted_at"),
                rs.getString("promoted_prey_id"),
                rs.getString("discovered_at"),
                rs.getString("last_checked_at"),
                rs.getString("effective_at"),
                rs.getString("closed_at"),
                rs.getString("abandoned_at"));
    }

    private JsonNode readJson(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize index_event snapshot JSON: {}", json, e);
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
