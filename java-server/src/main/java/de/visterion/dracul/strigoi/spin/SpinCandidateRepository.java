package de.visterion.dracul.strigoi.spin;

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
 * Persistence for tracked spin-off candidates (V26 {@code spin_candidate}).
 * JdbcClient-based, mirroring {@link de.visterion.dracul.prey.PreyRepository}:
 * explicit {@code INSERT ... ON CONFLICT DO NOTHING} for idempotent ingestion,
 * guarded compare-and-set UPDATEs for the forward-only lifecycle, and no Spring
 * Data JPA.
 *
 * <p>Idempotency and the {@link SpinoffScreener} dedup key both mirror the V26
 * expression unique index {@code COALESCE(cik, lower(company_name))}: one row per
 * spin-co, keyed on its CIK when known, degrading to the lowercased company name
 * before a ticker/CIK is available.
 */
@Repository
public class SpinCandidateRepository {

    private static final Logger log = LoggerFactory.getLogger(SpinCandidateRepository.class);

    /** Terminal states derived from the enum, so the "non-terminal" scan stays in sync. */
    private static final List<String> TERMINAL_STATUSES = Arrays.stream(SpinStatus.values())
            .filter(SpinStatus::isTerminal).map(Enum::name).toList();

    private static final String COLS = """
            id, cik, symbol, company_name, form_type, filing_date, filing_url,
            distribution_ratio, record_date, distribution_date, term_sheet_available,
            term_sheet_text, parent_symbol,
            status, registered_snapshot, distributed_snapshot, settled_snapshot,
            promoted_at, promoted_prey_id, discovered_at, last_checked_at,
            distributed_at, settled_at, abandoned_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SpinCandidateRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Ingests a screened candidate as a REGISTERED row. Idempotent: a row that
     * collides on the natural key ({@code COALESCE(cik, lower(company_name))}) is
     * skipped via ON CONFLICT DO NOTHING, so re-running the hunt never duplicates a
     * spin-co nor resets its lifecycle. Both cik and symbol are normalised to NULL
     * when blank — cik so a "" never becomes a real natural key (COALESCE('', name)
     * = ''), matching the screener's blank-cik-degrades-to-name behaviour; symbol as
     * "no ticker yet". Returns whether a new row was actually inserted.
     */
    public boolean upsertRegistered(SpinCandidate c) {
        int rows = jdbc.sql("""
                INSERT INTO spin_candidate
                  (cik, symbol, company_name, form_type, filing_date, filing_url, status)
                VALUES
                  (:cik, :symbol, :companyName, :formType, :filingDate::date, :filingUrl, 'REGISTERED')
                ON CONFLICT ((COALESCE(cik, lower(company_name)))) DO NOTHING
                """)
                .param("cik", emptyToNull(c.cik()))
                .param("symbol", emptyToNull(c.symbol()))
                .param("companyName", c.companyName())
                .param("formType", c.formType())
                .param("filingDate", c.filingDate())
                .param("filingUrl", c.filingUrl())
                .update();
        return rows > 0;
    }

    /**
     * Guarded forward-only status transition (compare-and-set on the current
     * status). Bumps {@code last_checked_at}, and stamps the transition's audit
     * timestamp ({@code distributed_at}/{@code settled_at}/{@code abandoned_at})
     * where the target stage has one. Never reverses: the {@code WHERE status = from}
     * guard makes concurrent/duplicate reconciliations no-ops. Returns whether the
     * row moved.
     */
    public boolean advanceStatus(long id, SpinStatus from, SpinStatus to) {
        String tsSet = switch (to) {
            case DISTRIBUTED -> ", distributed_at = now()";
            case SETTLED -> ", settled_at = now()";
            case ABANDONED -> ", abandoned_at = now()";
            default -> "";
        };
        int rows = jdbc.sql(
                "UPDATE spin_candidate SET status = :to, last_checked_at = now()" + tsSet
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
     * caller-supplied text), so there is no injection surface. The snapshot is
     * serialised with the shared {@link ObjectMapper}, exactly like
     * {@code PreyRepository} serialises its JSONB columns. A null snapshot clears the
     * column. Returns whether the row exists.
     */
    public boolean storeSnapshot(long id, SpinStatus stage, JsonNode snapshot) {
        String column = switch (stage) {
            case REGISTERED -> "registered_snapshot";
            case DISTRIBUTED -> "distributed_snapshot";
            case SETTLED -> "settled_snapshot";
            default -> throw new IllegalArgumentException("no snapshot column for stage " + stage);
        };
        String json;
        try {
            json = snapshot == null ? null : mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize spin_candidate snapshot", e);
        }
        int rows = jdbc.sql(
                "UPDATE spin_candidate SET " + column + " = :json::jsonb, last_checked_at = now()"
                        + " WHERE id = :id")
                .param("json", json)
                .param("id", id)
                .update();
        return rows > 0;
    }

    /**
     * Persists the distribution terms parsed from the 10-12B term sheet
     * ({@code distribution_ratio}/{@code record_date}/{@code distribution_date}/
     * {@code term_sheet_available}) and bumps {@code last_checked_at}. Ingestion
     * ({@link #upsertRegistered}) only writes the filing metadata; the term sheet is
     * fetched + parsed once during REGISTERED-stage enrichment (see
     * {@link SpinCandidateEnricher}) and stored here so the calendar reconciler has a
     * {@code record_date}/{@code distribution_date} to work with and the response payload
     * carries the structured terms. The raw {@code term_sheet_text} prose and the best-effort
     * {@code parent_symbol} are persisted alongside so the LLM gets the spin rationale and the
     * DISTRIBUTED-stage sizeRatio has a parent to key on. Dates are ISO strings cast to
     * {@code date} (null when the parser found none). Returns whether the row exists.
     */
    public boolean storeTerms(long id, String distributionRatio, String recordDate,
                              String distributionDate, boolean termSheetAvailable,
                              String termSheetText, String parentSymbol) {
        int rows = jdbc.sql("""
                UPDATE spin_candidate
                   SET distribution_ratio = :ratio,
                       record_date = :recordDate::date,
                       distribution_date = :distributionDate::date,
                       term_sheet_available = :available,
                       term_sheet_text = :text,
                       parent_symbol = :parent,
                       last_checked_at = now()
                 WHERE id = :id
                """)
                .param("ratio", emptyToNull(distributionRatio))
                .param("recordDate", emptyToNull(recordDate))
                .param("distributionDate", emptyToNull(distributionDate))
                .param("available", termSheetAvailable)
                .param("text", emptyToNull(termSheetText))
                .param("parent", emptyToNull(parentSymbol))
                .param("id", id)
                .update();
        return rows > 0;
    }

    /**
     * Active, not-yet-promoted rows for the LLM response payload: the tracked window
     * {REGISTERED, WHEN_ISSUED, DISTRIBUTED} with {@code promoted_at IS NULL}. Terminal rows
     * (SETTLED/ABANDONED) and already-promoted rows are excluded — the hunter only reasons over
     * candidates still worth a fresh look. Newest-discovered first so the freshest setups lead.
     */
    public List<SpinCandidateRow> findActiveUnpromoted(int limit) {
        return jdbc.sql("SELECT " + COLS + """
                FROM spin_candidate
                WHERE status IN ('REGISTERED', 'WHEN_ISSUED', 'DISTRIBUTED') AND promoted_at IS NULL
                ORDER BY discovered_at DESC
                LIMIT :limit
                """)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    /** Bumps {@code last_checked_at} without any state change (checked, nothing moved). */
    public boolean touchLastChecked(long id) {
        return jdbc.sql("UPDATE spin_candidate SET last_checked_at = now() WHERE id = :id")
                .param("id", id)
                .update() > 0;
    }

    /**
     * Non-terminal rows (everything but SETTLED/ABANDONED), oldest-checked first —
     * the reconciler's work queue. The ORDER BY last_checked_at ASC is served by
     * {@code idx_spin_candidate_last_checked}; the terminal-status filter is a residual.
     */
    public List<SpinCandidateRow> findNonTerminalOldestCheckedFirst(int limit) {
        return jdbc.sql("SELECT " + COLS + """
                FROM spin_candidate
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
     * DISTRIBUTED rows not yet promoted to prey, oldest-distributed first — the
     * promotion queue. Backed by the partial {@code idx_spin_candidate_promotable}
     * ((status, distributed_at) WHERE promoted_at IS NULL), which carries both the
     * status filter and the ordering.
     */
    public List<SpinCandidateRow> findDistributedUnpromoted(int limit) {
        return jdbc.sql("SELECT " + COLS + """
                FROM spin_candidate
                WHERE status = 'DISTRIBUTED' AND promoted_at IS NULL
                ORDER BY distributed_at ASC NULLS FIRST
                LIMIT :limit
                """)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    /**
     * The DISTRIBUTED, not-yet-promoted row carrying this symbol — the promotion lookup used by
     * {@link StrigoiSpinWebhookController#afterPersist} to match an emitted prey back to its tracked
     * candidate. Restricted to {@code status = 'DISTRIBUTED' AND promoted_at IS NULL} so a prey can
     * only ever promote a candidate that is in the forced-selling window and not already promoted;
     * a prey whose symbol matches no such row (untracked ticker, already-promoted, or non-DISTRIBUTED)
     * yields empty and is skipped fail-soft. Newest-distributed first for determinism if — vanishingly
     * rare — two tracked spin-cos ever shared a symbol. Blank symbol yields empty.
     */
    public Optional<SpinCandidateRow> findPromotableBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return jdbc.sql("SELECT " + COLS + """
                FROM spin_candidate
                WHERE status = 'DISTRIBUTED' AND promoted_at IS NULL AND symbol = :symbol
                ORDER BY distributed_at DESC NULLS LAST
                LIMIT 1
                """)
                .param("symbol", symbol)
                .query(this::mapRow)
                .optional();
    }

    /**
     * Guarded promotion stamp: sets {@code promoted_at = now()} and {@code promoted_prey_id} only
     * while the row is still unpromoted ({@code promoted_at IS NULL}). The CAS guard makes this
     * idempotent — a retried webhook delivery, or a second prey matching the same candidate, is a
     * no-op and never re-stamps a different prey id. Once stamped, the row drops out of
     * {@link #findActiveUnpromoted} and {@link #findPromotableBySymbol}, so the LLM never sees it
     * again and cannot re-emit it. Returns whether the row moved this call.
     */
    public boolean markPromoted(long id, String preyId) {
        int rows = jdbc.sql("""
                UPDATE spin_candidate
                   SET promoted_at = now(), promoted_prey_id = :preyId, last_checked_at = now()
                 WHERE id = :id AND promoted_at IS NULL
                """)
                .param("preyId", preyId)
                .param("id", id)
                .update();
        return rows > 0;
    }

    /** Single row by id (test/verification helper; also useful for the promotion path). */
    public Optional<SpinCandidateRow> findById(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM spin_candidate WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    private SpinCandidateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SpinCandidateRow(
                rs.getLong("id"),
                rs.getString("cik"),
                rs.getString("symbol"),
                rs.getString("company_name"),
                rs.getString("form_type"),
                rs.getObject("filing_date", LocalDate.class),
                rs.getString("filing_url"),
                rs.getString("distribution_ratio"),
                rs.getObject("record_date", LocalDate.class),
                rs.getObject("distribution_date", LocalDate.class),
                rs.getBoolean("term_sheet_available"),
                rs.getString("term_sheet_text"),
                rs.getString("parent_symbol"),
                SpinStatus.valueOf(rs.getString("status")),
                readJson(rs.getString("registered_snapshot")),
                readJson(rs.getString("distributed_snapshot")),
                readJson(rs.getString("settled_snapshot")),
                rs.getString("promoted_at"),
                rs.getString("promoted_prey_id"),
                rs.getString("discovered_at"),
                rs.getString("last_checked_at"),
                rs.getString("distributed_at"),
                rs.getString("settled_at"),
                rs.getString("abandoned_at"));
    }

    private JsonNode readJson(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize spin_candidate snapshot JSON: {}", json, e);
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
