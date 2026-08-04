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
