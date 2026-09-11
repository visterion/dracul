package de.visterion.dracul.executor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Persists the executor decision audit trail (one row per signal verdict). */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ExecutorDecisionRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutorDecisionRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ExecutorDecisionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long insert(ExecutorDecision d) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO executor_decision
                  (signal_id, symbol, accepted, reject_reason, veto_trace, rationale,
                   broker_order_id, run_id, action)
                VALUES (:signalId, :symbol, :accepted, :rejectReason, CAST(:vetoTrace AS jsonb), :rationale,
                        :brokerOrderId, :runId, :action)
                """)
                .param("signalId", d.signalId())
                .param("symbol", d.symbol())
                .param("accepted", d.accepted())
                .param("rejectReason", d.rejectReason())
                .param("vetoTrace", writeJson(d.vetoTrace()))
                .param("rationale", d.rationale())
                .param("brokerOrderId", d.brokerOrderId())
                .param("runId", d.runId())
                .param("action", d.action())
                .update(keyHolder, "id");
        return ((Number) keyHolder.getKeys().get("id")).longValue();
    }

    public int countByReason(String signalId, String rejectReason) {
        return jdbc.sql("""
                SELECT count(*) FROM executor_decision
                WHERE signal_id = :signalId AND reject_reason = :reason
                """)
                .param("signalId", signalId)
                .param("reason", rejectReason)
                .query(Integer.class)
                .single();
    }

    /**
     * The place-entry half of the {@code ADOPTION_AMBIGUOUS} once-per gate.
     *
     * <p>Two different refusals write {@code ADOPTION_AMBIGUOUS} rows under the SAME signal id:
     * the place-entry decision table (case D) and the add-tranche refusal that will not place a
     * second tranche next to a FILLED {@code t2-} order. They are told apart by
     * {@code broker_order_id}: place-entry rows carry NULL (case D refuses a whole observed state,
     * not one order), the tranche refusal carries the filled tranche order id. So this read gates
     * only place-entry and {@link #countByReasonAndBrokerOrder} gates only the tranche refusal —
     * an earlier place-entry ambiguity can no longer silence the double-exposure CRITICAL, and
     * vice versa.
     */
    public int countPlaceEntryAmbiguities(String signalId) {
        return jdbc.sql("""
                SELECT count(*) FROM executor_decision
                WHERE signal_id = :signalId AND reject_reason = 'ADOPTION_AMBIGUOUS'
                  AND broker_order_id IS NULL
                """)
                .param("signalId", signalId)
                .query(Integer.class)
                .single();
    }

    /**
     * Rows of this signal with this reason that name ONE specific broker order — the add-tranche
     * half of the gate described on {@link #countPlaceEntryAmbiguities}. A null
     * {@code brokerOrderId} never matches here; that case belongs to
     * {@link #countPlaceEntryAmbiguities}.
     */
    public int countByReasonAndBrokerOrder(String signalId, String rejectReason,
            String brokerOrderId) {
        return jdbc.sql("""
                SELECT count(*) FROM executor_decision
                WHERE signal_id = :signalId AND reject_reason = :reason
                  AND broker_order_id = :brokerOrderId
                """)
                .param("signalId", signalId)
                .param("reason", rejectReason)
                .param("brokerOrderId", brokerOrderId)
                .query(Integer.class)
                .single();
    }

    /**
     * Broker errors of this signal inside ONE run — the short-term throttle axis.
     *
     * <p>Distinct from {@link #countDistinctRunsByReasonSince}: that one answers "on how many
     * nights did this signal fail?", this one answers "how often did we already call the broker
     * for it tonight?".
     */
    public int countByReasonInRun(String signalId, String rejectReason, String runId) {
        return jdbc.sql("""
                SELECT count(*) FROM executor_decision
                WHERE signal_id = :signalId AND reject_reason = :reason AND run_id = :runId
                """)
                .param("signalId", signalId)
                .param("reason", rejectReason)
                .param("runId", runId)
                .query(Integer.class)
                .single();
    }

    /**
     * Number of DISTINCT runs in which this signal hit {@code rejectReason} after {@code since}.
     *
     * <p>Counting runs rather than rows is the whole point: the agent may call the broker several
     * times within one run, and a retry storm (429 → duplicate → 429) used to write three rows in
     * a single night. With {@code count(*)} that exhausted a lifetime cap of 3 immediately —
     * STT was locked out of tranche 2 from 2026-07-22 onward by exactly this.
     *
     * <p>Rows with a NULL {@code run_id} drop out of {@code count(DISTINCT run_id)} by definition,
     * which is intended: without a run there is no attempt axis, so such a row must not count as
     * an attempt.
     *
     * <p>The window bound is strict ({@code >}), so a row exactly on {@code since} is outside.
     */
    public int countDistinctRunsByReasonSince(String signalId, String rejectReason, Instant since) {
        return jdbc.sql("""
                SELECT count(DISTINCT run_id) FROM executor_decision
                WHERE signal_id = :signalId AND reject_reason = :reason AND created_at > :since
                """)
                .param("signalId", signalId)
                .param("reason", rejectReason)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    public List<ExecutorDecision> findRecent(int limit) {
        return jdbc.sql("""
                SELECT * FROM executor_decision
                ORDER BY created_at DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    /**
     * SKIP verdicts the LLM issued without a {@code place_entry}, and whose signal carries the
     * two counterfactual reference inputs. These rows are invisible to
     * {@code OutcomeBatchJob.processCounterfactuals}, which reads {@code decision_log} REJECT rows
     * only: a bare {@code submit_decision} SKIP writes nothing there. 202 of 242 SKIPPED signals
     * were outside the learning loop for this reason.
     *
     * <p>Each predicate earns its place:
     * <ul>
     *   <li>{@code reject_reason IS NULL} — a row WITH one is a code-gate reject, not an LLM verdict.</li>
     *   <li>{@code reference_bar_date/reference_atr IS NOT NULL} — the walk has no anchor without
     *       them, and pre-V49 rows have neither. Forward-only by decision; no backfill.</li>
     *   <li>The {@code NOT EXISTS} matches {@code action = 'REJECT'} SPECIFICALLY, mirroring exactly
     *       what {@code processCounterfactuals} consumes: when place_entry already vetoed the signal
     *       in the same run, the VETO REASON WINS and the LLM's SKIP is not counted a second time;
     *       but a signal whose only decision_log row is some other action (e.g. ADD_TRANCHE_REJECT)
     *       must not fall out of both loops.</li>
     * </ul>
     */
    public List<ExecutorDecision> findSkipsWithoutDecisionLog() {
        return jdbc.sql("""
                SELECT d.* FROM executor_decision d
                JOIN executor_signal s ON s.signal_id = d.signal_id
                WHERE d.action = 'SKIP' AND d.reject_reason IS NULL AND d.signal_id IS NOT NULL
                  AND s.reference_bar_date IS NOT NULL AND s.reference_atr IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM decision_log l
                                  WHERE l.signal_id = d.signal_id
                                    AND l.trigger_type = 'SIGNAL' AND l.action = 'REJECT')
                ORDER BY d.created_at ASC
                """)
                .query(this::mapRow)
                .list();
    }

    /**
     * Signals the {@code PendingSignalSweeper} retired that the LLM never called
     * {@code place_entry} on, and whose signal carries the two counterfactual reference inputs.
     * Feeds the {@code SIGNAL_EXPIRED_UNEVALUATED} loop in {@code OutcomeBatchJob}.
     *
     * <p>Each predicate earns its place:
     * <ul>
     *   <li>{@code action = 'SWEEP'} — the structural discriminator against {@code place_entry}'s
     *       own {@code SIGNAL_EXPIRED} row, which carries {@code action = null}, a full catalog
     *       trace and a {@code decision_log} REJECT partner. Bound to
     *       {@code PendingSignalSweeper.ACTION}: {@code ExecutorDecisionRepositoryIT} seeds its
     *       positive row from that constant, so a drift breaks a test rather than silently
     *       emptying this finder (whose empty result is also its steady state on most nights).</li>
     *   <li>{@code reference_bar_date/reference_atr IS NOT NULL} — the walk has no anchor without
     *       them, and pre-V49 rows have neither. Forward-only by decision; no backfill.</li>
     *   <li>The {@code NOT EXISTS} matches {@code trigger_type='SIGNAL'} AND {@code action='REJECT'}
     *       SPECIFICALLY: a signal stranded by a transient {@code place_entry} reject already has a
     *       {@code processReject} counterfactual under that veto reason, and the VETO REASON WINS —
     *       exactly the rule {@link #findSkipsWithoutDecisionLog} applies to skips. A signal whose
     *       only partner is a SIGNAL/ENTER row (the accepting interleaving) or a MAINTENANCE row
     *       must NOT fall out of both loops, so the predicate stays narrow.</li>
     * </ul>
     *
     * <p>{@code DISTINCT ON (d.signal_id)} because one signal can carry two {@code SWEEP} rows (a
     * {@code markStatus} failure after the insert, or an operator pass overlapping the agent's).
     * The {@code outcome_log} upsert is keyed on {@code log_id_ref} anyway, so a second row would
     * only cost a redundant OHLC fetch.
     *
     * <p>Ordering note: the result is ordered by {@code d.signal_id} (UUID order, a consequence of
     * {@code DISTINCT ON}), NOT chronologically — unlike {@link #findSkipsWithoutDecisionLog}, which
     * orders by {@code created_at}. Consumers must not rely on this method's rows arriving in any
     * meaningful order.
     */
    public List<ExecutorDecision> findSweptWithoutDecisionLog() {
        return jdbc.sql("""
                SELECT DISTINCT ON (d.signal_id) d.* FROM executor_decision d
                JOIN executor_signal s ON s.signal_id = d.signal_id
                WHERE d.action = :sweepAction AND d.reject_reason = 'SIGNAL_EXPIRED'
                  AND s.reference_bar_date IS NOT NULL AND s.reference_atr IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM decision_log l
                                  WHERE l.signal_id = d.signal_id
                                    AND l.trigger_type = 'SIGNAL' AND l.action = 'REJECT')
                ORDER BY d.signal_id, d.created_at ASC
                """)
                .param("sweepAction", PendingSignalSweeper.ACTION)
                .query(this::mapRow)
                .list();
    }

    private ExecutorDecision mapRow(ResultSet rs, int n) throws SQLException {
        Object createdAtObj = rs.getObject("created_at");
        return new ExecutorDecision(
                rs.getLong("id"),
                rs.getString("signal_id"),
                rs.getString("symbol"),
                rs.getBoolean("accepted"),
                rs.getString("reject_reason"),
                readList(rs.getString("veto_trace")),
                rs.getString("rationale"),
                rs.getString("broker_order_id"),
                rs.getString("run_id"),
                createdAtObj == null ? null : createdAtObj.toString(),
                rs.getString("action"));
    }

    private String writeJson(List<String> v) {
        try { return mapper.writeValueAsString(v == null ? List.of() : v); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize executor-decision vetoTrace", e); }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return List.of();
        }
    }
}
