package de.visterion.dracul.outcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Persists {@code outcome_log} rows. Upserts by {@code log_id_ref} (the decision_log row this
 * outcome/counterfactual traces back to), so re-runs of {@link OutcomeBatchJob} refine an
 * existing row instead of duplicating it.
 */
@Repository
public class OutcomeLogRepository {

    private static final Logger log = LoggerFactory.getLogger(OutcomeLogRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutcomeLogRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void upsert(OutcomeLogRow r) {
        jdbc.sql("""
                INSERT INTO outcome_log
                  (kind, log_id_ref, position_id, symbol, reason_code, filled, fill_price,
                   slippage_vs_limit, holding_days, mfe_r, mae_r, realized_r, exit_trigger,
                   exit_log_id, partial_exits, reentry_within_10d, roundtrip_under_5d,
                   hypothetical, hunter_label, source_agent, agent_version, rule_version,
                   complete, computed_at)
                VALUES (:kind, :logIdRef, :positionId, :symbol, :reasonCode, :filled, :fillPrice,
                        :slippageVsLimit, :holdingDays, :mfeR, :maeR, :realizedR, :exitTrigger,
                        :exitLogId, CAST(:partialExits AS jsonb), :reentryWithin10d,
                        :roundtripUnder5d, CAST(:hypothetical AS jsonb), :hunterLabel,
                        :sourceAgent, :agentVersion, :ruleVersion, :complete, now())
                ON CONFLICT (log_id_ref) DO UPDATE SET
                  kind = EXCLUDED.kind,
                  position_id = EXCLUDED.position_id,
                  symbol = EXCLUDED.symbol,
                  reason_code = EXCLUDED.reason_code,
                  filled = EXCLUDED.filled,
                  fill_price = EXCLUDED.fill_price,
                  slippage_vs_limit = EXCLUDED.slippage_vs_limit,
                  holding_days = EXCLUDED.holding_days,
                  mfe_r = EXCLUDED.mfe_r,
                  mae_r = EXCLUDED.mae_r,
                  realized_r = EXCLUDED.realized_r,
                  exit_trigger = EXCLUDED.exit_trigger,
                  exit_log_id = EXCLUDED.exit_log_id,
                  partial_exits = EXCLUDED.partial_exits,
                  reentry_within_10d = EXCLUDED.reentry_within_10d,
                  roundtrip_under_5d = EXCLUDED.roundtrip_under_5d,
                  hypothetical = EXCLUDED.hypothetical,
                  hunter_label = EXCLUDED.hunter_label,
                  source_agent = EXCLUDED.source_agent,
                  agent_version = EXCLUDED.agent_version,
                  rule_version = EXCLUDED.rule_version,
                  complete = EXCLUDED.complete,
                  computed_at = now()
                """)
                .param("kind", r.kind())
                .param("logIdRef", r.logIdRef())
                .param("positionId", r.positionId())
                .param("symbol", r.symbol())
                .param("reasonCode", r.reasonCode())
                .param("filled", r.filled())
                .param("fillPrice", r.fillPrice())
                .param("slippageVsLimit", r.slippageVsLimit())
                .param("holdingDays", r.holdingDays())
                .param("mfeR", r.mfeR())
                .param("maeR", r.maeR())
                .param("realizedR", r.realizedR())
                .param("exitTrigger", r.exitTrigger())
                .param("exitLogId", r.exitLogId())
                .param("partialExits", writeJson(r.partialExits(), "[]"))
                .param("reentryWithin10d", r.reentryWithin10d())
                .param("roundtripUnder5d", r.roundtripUnder5d())
                .param("hypothetical", writeJson(r.hypothetical(), "{}"))
                .param("hunterLabel", r.hunterLabel())
                .param("sourceAgent", r.sourceAgent())
                .param("agentVersion", r.agentVersion())
                .param("ruleVersion", r.ruleVersion())
                .param("complete", r.complete())
                .update();
    }

    /** True when a row already exists for {@code logIdRef} AND is marked complete — the batch
     *  job skips re-processing in that case (closed-position TRADE rows never change again;
     *  COUNTERFACTUAL rows stay incomplete until skipped or the 60-bar window is filled). */
    public boolean isComplete(String logIdRef) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT complete FROM outcome_log WHERE log_id_ref = :ref")
                .param("ref", logIdRef)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    /** Full COMPLETE TRADE row for {@code positionId}, or null if no TRADE row exists yet for
     *  that position or it is still within {@link OutcomeBatchJob}'s 14-calendar-day re-entry
     *  window. {@link OutcomeMemoryScanJob} gates its HiveMem cell write on this: the position's
     *  own {@code realized_r} is the un-weighted, possibly-stale figure at close, while this row
     *  carries the FINAL quantity-weighted value once trims/re-entries have settled. */
    public OutcomeLogRow findCompleteTradeByPositionId(long positionId) {
        return jdbc.sql("""
                SELECT * FROM outcome_log
                WHERE position_id = :positionId AND kind = 'TRADE' AND complete = true
                """)
                .param("positionId", positionId)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    /** Test/debug accessor: full row by log_id_ref, or null. */
    public OutcomeLogRow findByLogIdRef(String logIdRef) {
        return jdbc.sql("SELECT * FROM outcome_log WHERE log_id_ref = :ref")
                .param("ref", logIdRef)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    List<OutcomeLogRow> findAll() {
        return jdbc.sql("SELECT * FROM outcome_log ORDER BY computed_at ASC")
                .query(this::mapRow)
                .list();
    }

    // ---------------------------------------------------------------------------------------
    // Analytics aggregates (Task 10) — read-only queries feeding CalibrationController. SQL
    // aggregation/filtering lives here; the math (Brier score, percentiles, grouping) lives in
    // CalibrationService.
    // ---------------------------------------------------------------------------------------

    /** (confidence_in_decision, realized_r > 0) pairs for completed TRADE outcomes, joined back
     *  to the ENTER decision row that opened the position. Feeds the executor-level Brier score. */
    public List<CalibrationService.BrierPoint> findExecutorBrierPoints() {
        return jdbc.sql("""
                SELECT dl.confidence_in_decision AS predicted, ol.realized_r AS realized_r
                FROM outcome_log ol
                JOIN decision_log dl ON dl.log_id::text = ol.log_id_ref
                WHERE ol.kind = 'TRADE' AND ol.complete = true
                  AND dl.action = 'ENTER'
                  AND dl.confidence_in_decision IS NOT NULL
                  AND ol.realized_r IS NOT NULL
                """)
                .query((rs, n) -> new CalibrationService.BrierPoint(
                        rs.getDouble("predicted"), rs.getBigDecimal("realized_r").signum() > 0))
                .list();
    }

    /** (hunter agent, predicted signal_confidence, hunter_label) triples for every outcome_log
     *  row (TRADE or COUNTERFACTUAL) whose ENTER/REJECT decision row carries a signal
     *  confidence. Feeds the per-hunter Brier score. */
    public List<CalibrationService.AgentBrierPoint> findHunterBrierPoints() {
        return jdbc.sql("""
                SELECT ol.source_agent AS agent,
                       (dl.inputs_snapshot ->> 'signal_confidence') AS predicted,
                       ol.hunter_label AS hunter_label
                FROM outcome_log ol
                JOIN decision_log dl ON dl.log_id::text = ol.log_id_ref
                WHERE dl.trigger_type = 'SIGNAL'
                  AND ol.source_agent IS NOT NULL
                  AND ol.hunter_label IS NOT NULL
                  AND dl.inputs_snapshot ->> 'signal_confidence' IS NOT NULL
                """)
                .query((rs, n) -> new CalibrationService.AgentBrierPoint(
                        rs.getString("agent"),
                        Double.parseDouble(rs.getString("predicted")),
                        rs.getBoolean("hunter_label")))
                .list();
    }

    /**
     * One row per (signal, reason_code) COUNTERFACTUAL outcome, carrying the hypothetical fields
     * veto-precision stats need. Skipped rows still come back (skipped=true, null hypothetical
     * fields) so the caller can count them separately from the means.
     *
     * <p><b>Why DISTINCT ON.</b> {@code place_entry} retries of the SAME signal each wrote their
     * own counterfactual, so one signal could contribute 16 rows (ISRG, COOLDOWN) and inflate
     * {@code vetoPrecision}. The key is the SIGNAL, not the symbol: P (4 rows / 4 signals) and
     * PAYO (3 / 3) are genuinely distinct signals and must all survive. {@code LLM_SKIP} rows have
     * no {@code decision_log} partner and key on their own {@code log_id_ref}
     * ({@code "skip:" + signal_id}), which is already one per signal — hence the COALESCE.
     *
     * <p>Tiebreak: the COMPLETE row if there is one, else the most recently computed;
     * {@code ol.id DESC} is only a deterministic last resort ({@code id} is a UUID and carries no
     * time information). The WRITE side stays keyed per {@code log_id_ref} — this is a read-side
     * dedupe only.
     *
     * <p><b>Why ACCEPTED signals are excluded.</b> An ACCEPTED signal has an
     * {@code executor_position}, and its question "what if we had taken it" is answered by the
     * trade — once that trade completes and its TRADE outcome row enters the Brier population.
     * Until then the counterfactual is <i>withheld</i>, not replaced: a reason's {@code n} can drop
     * by one without an {@code n} gain elsewhere in the same report for as long as the position is
     * open. That is the honest state; counting a hypothetical next to a live real position would be
     * the worse error. EXPIRED (bracket placed, never filled), REJECTED and SKIPPED signals never
     * traded, so their counterfactual stays. {@code s.status IS NULL} keeps rows whose signal id no
     * longer resolves exactly as before. The exclusion is {@code veto_precision} ONLY — the hunter
     * Brier ({@link #findHunterBrierPoints}) deliberately keeps these rows, because it asks whether
     * the SIGNAL was good, which a hypothetical answers even when the executor entered.
     *
     * <p><b>Why STALE_FILL and ADOPTION_AMBIGUOUS are excluded too.</b> Neither is a veto: nothing
     * about the signal was judged and rejected on merit. {@code STALE_FILL} says a trade already
     * happened and the book never saw it; {@code ADOPTION_AMBIGUOUS} says the book could not be
     * reconciled to the broker. Counting either as "a veto that turned out right/wrong" measures
     * the executor's bookkeeping, not its judgement, and would move {@code veto_precision} and its
     * per-reason {@code skipped} counts on {@code GET /api/executor/behavior} for reasons a reader
     * of that report cannot act on. Like the ACCEPTED exclusion, this is {@code veto_precision}
     * ONLY: {@link #findHunterBrierPoints} keeps both, because the hunter's question — was the
     * SIGNAL good? — still has an answer, and for a {@code STALE_FILL} it is the one class where a
     * real trade is KNOWN to have happened. {@code OutcomeBatchJob} is unchanged: cases C and D
     * write ordinary {@code SIGNAL}/{@code REJECT} decision rows and become counterfactuals exactly
     * like {@code BROKER_ERROR} ones.
     */
    public List<CalibrationService.VetoRow> findVetoRows() {
        return jdbc.sql("""
                SELECT DISTINCT ON (COALESCE(dl.signal_id, ol.log_id_ref), ol.reason_code)
                       ol.reason_code AS reason_code,
                       (ol.hypothetical->>'skipped_reason') IS NOT NULL AS skipped,
                       ol.hypothetical->>'r_after_20d' AS r_after_20d,
                       ol.hypothetical->>'r_after_60d' AS r_after_60d,
                       ol.hypothetical->>'would_have_stopped_out' AS would_have_stopped_out
                FROM outcome_log ol
                LEFT JOIN decision_log dl ON dl.log_id::text = ol.log_id_ref
                LEFT JOIN executor_signal s
                       ON s.signal_id = COALESCE(dl.signal_id,
                                                 CASE WHEN ol.log_id_ref LIKE 'skip:%'    THEN substr(ol.log_id_ref, 6)
                                                      WHEN ol.log_id_ref LIKE 'expired:%' THEN substr(ol.log_id_ref, 9) END)
                WHERE ol.kind='COUNTERFACTUAL' AND ol.reason_code IS NOT NULL
                  AND (s.status IS NULL OR s.status <> 'ACCEPTED')
                  AND ol.reason_code NOT IN ('STALE_FILL','ADOPTION_AMBIGUOUS')
                ORDER BY COALESCE(dl.signal_id, ol.log_id_ref), ol.reason_code,
                         ol.complete DESC, ol.computed_at DESC, ol.id DESC
                """)
                .query((rs, n) -> new CalibrationService.VetoRow(
                        rs.getString("reason_code"),
                        rs.getBoolean("skipped"),
                        parseDouble(rs.getString("r_after_20d")),
                        parseDouble(rs.getString("r_after_60d")),
                        parseBoolean(rs.getString("would_have_stopped_out"))))
                .list();
    }

    /** trigger_to_order_seconds latency (seconds) of every HARD_TRIGGER decision row. */
    public List<Long> findHardTriggerLatencySeconds() {
        return jdbc.sql("""
                SELECT (latency ->> 'trigger_to_order_seconds') AS seconds
                FROM decision_log
                WHERE trigger_type = 'HARD_TRIGGER'
                  AND latency ->> 'trigger_to_order_seconds' IS NOT NULL
                """)
                .query((rs, n) -> Long.parseLong(rs.getString("seconds")))
                .list();
    }

    /** (reentry_within_10d, roundtrip_under_5d) flag pairs of every TRADE outcome row. */
    public List<CalibrationService.WhipsawRowPair> findWhipsawFlags() {
        return jdbc.sql("""
                SELECT reentry_within_10d, roundtrip_under_5d
                FROM outcome_log
                WHERE kind = 'TRADE'
                """)
                .query((rs, n) -> new CalibrationService.WhipsawRowPair(
                        (Boolean) rs.getObject("reentry_within_10d"),
                        (Boolean) rs.getObject("roundtrip_under_5d")))
                .list();
    }

    /** Raw stop_basis string (from the ENTER order) + realized/MAE R of every completed TRADE
     *  outcome. Feeds the stop-basis comparison table. */
    public List<CalibrationService.StopBasisRow> findStopBasisRows() {
        return jdbc.sql("""
                SELECT (dl.order_json ->> 'stop_basis') AS stop_basis,
                       ol.realized_r AS realized_r, ol.mae_r AS mae_r
                FROM outcome_log ol
                JOIN decision_log dl ON dl.log_id::text = ol.log_id_ref
                WHERE ol.kind = 'TRADE' AND ol.complete = true
                  AND dl.action = 'ENTER'
                  AND dl.order_json ->> 'stop_basis' IS NOT NULL
                  AND ol.realized_r IS NOT NULL AND ol.mae_r IS NOT NULL
                """)
                .query((rs, n) -> new CalibrationService.StopBasisRow(
                        rs.getString("stop_basis"),
                        rs.getBigDecimal("realized_r").doubleValue(),
                        rs.getBigDecimal("mae_r").doubleValue()))
                .list();
    }

    /** slippage_vs_limit of every TRADE outcome row that has one. */
    public List<Double> findSlippageValues() {
        return jdbc.sql("""
                SELECT slippage_vs_limit FROM outcome_log
                WHERE kind = 'TRADE' AND slippage_vs_limit IS NOT NULL
                """)
                .query((rs, n) -> rs.getBigDecimal("slippage_vs_limit").doubleValue())
                .list();
    }

    private static Double parseDouble(String s) {
        return s == null ? null : Double.parseDouble(s);
    }

    private static Boolean parseBoolean(String s) {
        return s == null ? null : Boolean.parseBoolean(s);
    }

    private OutcomeLogRow mapRow(ResultSet rs, int n) throws SQLException {
        Object positionIdObj = rs.getObject("position_id");
        return new OutcomeLogRow(
                rs.getString("kind"),
                rs.getString("log_id_ref"),
                positionIdObj == null ? null : rs.getLong("position_id"),
                rs.getString("symbol"),
                rs.getString("reason_code"),
                (Boolean) rs.getObject("filled"),
                rs.getBigDecimal("fill_price"),
                rs.getBigDecimal("slippage_vs_limit"),
                (Integer) rs.getObject("holding_days"),
                rs.getBigDecimal("mfe_r"),
                rs.getBigDecimal("mae_r"),
                rs.getBigDecimal("realized_r"),
                rs.getString("exit_trigger"),
                rs.getString("exit_log_id"),
                readJson(rs.getString("partial_exits")),
                (Boolean) rs.getObject("reentry_within_10d"),
                (Boolean) rs.getObject("roundtrip_under_5d"),
                readJson(rs.getString("hypothetical")),
                (Boolean) rs.getObject("hunter_label"),
                rs.getString("source_agent"),
                rs.getString("agent_version"),
                rs.getString("rule_version"),
                rs.getBoolean("complete"));
    }

    private String writeJson(JsonNode node, String fallback) {
        try { return mapper.writeValueAsString(node == null ? mapper.readTree(fallback) : node); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize outcome-log JSON", e); }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return null;
        }
    }
}
