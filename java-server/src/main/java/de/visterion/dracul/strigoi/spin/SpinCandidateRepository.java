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
 * explicit {@code INSERT ... ON CONFLICT DO UPDATE} for idempotent, self-healing
 * ingestion (see {@link #upsertRegistered}), guarded compare-and-set UPDATEs for
 * the forward-only lifecycle, and no Spring Data JPA.
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
            distributed_at, settled_at, abandoned_at, terms_checked_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SpinCandidateRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Ingests a screened candidate as a REGISTERED row. Idempotent: a row that
     * collides on the natural key ({@code COALESCE(cik, lower(company_name))}) does
     * not get re-inserted nor lose its lifecycle — but it DOES get a chance to
     * backfill its {@code symbol}: {@code ON CONFLICT ... DO UPDATE SET symbol =
     * COALESCE(spin_candidate.symbol, EXCLUDED.symbol)} only ever fills a NULL
     * symbol, never overwrites one already set. This exists because a ticker can
     * legitimately arrive later — Agora's EFTS reader has historically failed to
     * resolve it on the filing that first created the row — and re-running the hunt
     * over the same filing is the only way that ticker ever lands. Deliberately
     * NOT carried over on conflict: {@code company_name} and {@code filing_url}. For
     * a row with {@code cik IS NULL}, {@code company_name} (lowercased) IS the
     * conflict key itself; mutating it inside the same DO UPDATE would move the row
     * out of its own index value. Both cik and symbol are normalised to NULL
     * when blank — cik so a "" never becomes a real natural key (COALESCE('', name)
     * = ''), matching the screener's blank-cik-degrades-to-name behaviour; symbol as
     * "no ticker yet". Returns whether a new row was actually inserted (not merely
     * updated) — computed via {@code RETURNING (xmax = 0) AS inserted}, since
     * Postgres's regular affected-row count no longer distinguishes INSERT from
     * UPDATE now that the conflict path can also touch a row.
     *
     * <p><b>The {@code SET} clause must stay unconditional — never add a
     * {@code WHERE spin_candidate.symbol IS NULL}.</b> Postgres requires an
     * {@code ON CONFLICT ... DO UPDATE} to affect the conflicting row for
     * {@code RETURNING} to produce anything; a {@code WHERE} that excludes rows
     * whose symbol is already set would make every identical-replay upsert (the
     * common case — most hunts re-ingest a row with no new information) match zero
     * rows, and {@code .query(Boolean.class).single()} would then throw
     * {@code EmptyResultDataAccessException} in production instead of returning
     * {@code false}. The {@code COALESCE} inside the unconditional {@code SET} is
     * what already makes the write a no-op when the symbol is unchanged — the
     * {@code WHERE} is not needed for correctness and would only break this method.
     */
    public boolean upsertRegistered(SpinCandidate c) {
        Boolean inserted = jdbc.sql("""
                INSERT INTO spin_candidate
                  (cik, symbol, company_name, form_type, filing_date, filing_url, status)
                VALUES
                  (:cik, :symbol, :companyName, :formType, :filingDate::date, :filingUrl, 'REGISTERED')
                ON CONFLICT ((COALESCE(cik, lower(company_name)))) DO UPDATE
                   SET symbol = COALESCE(spin_candidate.symbol, EXCLUDED.symbol)
                RETURNING (xmax = 0) AS inserted
                """)
                .param("cik", emptyToNull(c.cik()))
                .param("symbol", emptyToNull(c.symbol()))
                .param("companyName", c.companyName())
                .param("formType", c.formType())
                .param("filingDate", c.filingDate())
                .param("filingUrl", c.filingUrl())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(inserted);
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
     * {@code date} (null when the parser found none). Also stamps {@code terms_checked_at} (V44):
     * a successful capture must arm the 7-day throttle exactly like a failed one
     * ({@link #touchTermsChecked}), or the very next enrichment run would re-fetch immediately —
     * since D2, {@code record_date}/{@code distribution_date} never become non-null on their own,
     * so this stamp is the only thing that ever makes the capture precondition false again.
     * Returns whether the row exists.
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
                       last_checked_at = now(),
                       terms_checked_at = now()
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
     * Active, not-yet-promoted rows for the LLM response payload, restricted to the hunt's
     * requested window: the tracked statuses {REGISTERED, WHEN_ISSUED, DISTRIBUTED} with
     * {@code promoted_at IS NULL}. Terminal rows (SETTLED/ABANDONED) and already-promoted rows are
     * excluded — the hunter only reasons over candidates still worth a fresh look.
     * Newest-discovered first so the freshest setups lead.
     *
     * <p>There is deliberately NO window-blind variant of this query. The one that existed was the
     * whole D11 bug: the hunt's {@code lookback_days} reached the EDGAR ingest search but not the
     * response, so a 14-day and a 90-day request returned the identical rows.
     *
     * <p>Which date the window applies to. {@code lookback_days} is already an EDGAR FILING-DATE
     * window on the ingest side ({@code searchSpinoffs(to.minusDays(lookback), to)}), so
     * {@code filing_date} is the primary clock — one parameter must not mean two different things
     * at the two ends of the same hunt. But a spin-off's tradeable event is the DISTRIBUTION,
     * which lands weeks or months after the 10-12B registration; dropping a freshly-distributed
     * spin-co because its registration is old would delete exactly the candidates this hunter
     * exists for. A row is therefore in-window when EITHER date falls inside it.
     *
     * <p>{@code distributed_at} joins the same OR for the same reason, one level more defensive:
     * {@code distribution_date} is parsed from the 10-12B term sheet prose and is frequently
     * absent — no "record date" or "distribution date" language in the filing at all — so it
     * cannot be relied on to carry a freshly-distributed row into the window by itself. The
     * {@link SpinLifecycleReconciler} price probe stamps {@code distributed_at = now()} the
     * moment it moves a row to DISTRIBUTED, independent of whether the term sheet ever yielded a
     * date. Without this clause a row whose {@code filing_date} predates the window and whose
     * {@code distribution_date} is NULL transitions to DISTRIBUTED and then silently never
     * reaches the LLM — exactly the failure this method exists to prevent for {@code filing_date}.
     *
     * <p>Both `distribution_date` and `distributed_at` in a single OR is deliberate belt-and-braces,
     * not redundancy: the term sheet's stated date (when parsed) and the reconciler's own
     * transition timestamp are two independent signals for "this just became tradeable", and
     * either one is sufficient to keep a candidate in front of the hunter.
     *
     * <p>{@code discovered_at} is deliberately NOT the filter: it records when Dracul's cron first
     * saw the row, not when anything happened in the market — a backfill or a re-ingest would make
     * every row look brand new. It stays the ORDER BY (freshest setups lead), as before.
     *
     * <p>{@code cik} is the TIEBREAKER, and it is not decoration: one ingest pass inserts its rows
     * within ~25 ms of each other, well inside what {@code timestamptz} distinguishes for a shared
     * batch, so {@code discovered_at DESC} alone left ties to whatever order the scan happened to
     * produce. The {@code LIMIT} then cut a different, unpredictable subset from run to run and a
     * candidate could appear and vanish with nothing having changed. {@code cik} is the natural
     * key — unique, never null, and stable across re-ingests — so it makes the cut reproducible.
     *
     * <p>A row carrying NEITHER date is returned regardless. It cannot be judged by a date window,
     * and dropping it would be a silent loss of exactly the kind this query exists to end.
     */
    public List<SpinCandidateRow> findActiveUnpromotedInWindow(LocalDate since, int limit) {
        return jdbc.sql("SELECT " + COLS + """
                FROM spin_candidate
                WHERE status IN ('REGISTERED', 'WHEN_ISSUED', 'DISTRIBUTED') AND promoted_at IS NULL
                  AND (filing_date >= :since
                       OR distribution_date >= :since
                       OR distributed_at >= :since
                       OR (filing_date IS NULL AND distribution_date IS NULL))
                ORDER BY discovered_at DESC, cik DESC
                LIMIT :limit
                """)
                .param("since", since)
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
     * Stamps {@code terms_checked_at} (V44) ALONE — no term fields change. Used by
     * {@link SpinCandidateEnricher} for every term-capture attempt that did NOT call
     * {@link #storeTerms} (fetch failed, or the resolved document was not the requested exhibit
     * while the row already held good text): the 7-day throttle must still arm, or the row would
     * be re-fetched on the very next run. See the trap this guards against in {@link #storeTerms}.
     */
    public boolean touchTermsChecked(long id) {
        return jdbc.sql("UPDATE spin_candidate SET terms_checked_at = now() WHERE id = :id")
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
     * {@link #findActiveUnpromotedInWindow} and {@link #findPromotableBySymbol}, so the LLM never sees it
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
                rs.getString("abandoned_at"),
                rs.getTimestamp("terms_checked_at") == null ? null : rs.getTimestamp("terms_checked_at").toInstant());
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
