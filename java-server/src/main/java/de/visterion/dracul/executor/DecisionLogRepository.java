package de.visterion.dracul.executor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persists the rich decision-log audit trail (one row per executor decision). */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class DecisionLogRepository {

    private static final Logger log = LoggerFactory.getLogger(DecisionLogRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public DecisionLogRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(DecisionLog d) {
        String logId = d.logId() != null ? d.logId() : UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO decision_log
                  (log_id, run_id, rule_version, trigger_type, signal_id, source_agent,
                   source_agent_version, symbol, inputs_snapshot, veto_results, action,
                   reason_code, order_json, reasoning, confidence_in_decision, latency)
                VALUES (CAST(:logId AS uuid), :runId, :ruleVersion, :triggerType, :signalId, :sourceAgent,
                        :sourceAgentVersion, :symbol, CAST(:inputsSnapshot AS jsonb),
                        CAST(:vetoResults AS jsonb), :action, :reasonCode,
                        CAST(:orderJson AS jsonb), :reasoning, :confidenceInDecision,
                        CAST(:latency AS jsonb))
                """)
                .param("logId", logId)
                .param("runId", d.runId())
                .param("ruleVersion", d.ruleVersion())
                .param("triggerType", d.triggerType())
                .param("signalId", d.signalId())
                .param("sourceAgent", d.sourceAgent())
                .param("sourceAgentVersion", d.sourceAgentVersion())
                .param("symbol", d.symbol())
                .param("inputsSnapshot", writeJson(d.inputsSnapshot(), "{}"))
                .param("vetoResults", writeJson(d.vetoResults(), "[]"))
                .param("action", d.action())
                .param("reasonCode", d.reasonCode())
                .param("orderJson", writeJsonOrNull(d.orderJson()))
                .param("reasoning", d.reasoning())
                .param("confidenceInDecision", d.confidenceInDecision())
                .param("latency", writeJsonOrNull(d.latency()))
                .update();
    }

    public List<DecisionLog> findRecent(int limit) {
        return jdbc.sql("""
                SELECT * FROM decision_log
                ORDER BY created_at DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    /** All {@code trigger_type='SIGNAL'} rows with the given action (e.g. {@code ENTER} or
     *  {@code REJECT}), oldest first — used by the outcome batch job (Task 9) to walk entries
     *  and rejects that need a {@code outcome_log} row. */
    public List<DecisionLog> findSignalRowsByAction(String action) {
        return jdbc.sql("""
                SELECT * FROM decision_log
                WHERE trigger_type = 'SIGNAL' AND action = :action
                ORDER BY created_at ASC
                """)
                .param("action", action)
                .query(this::mapRow)
                .list();
    }

    /** The most recent decision row for a given signal id + action (used to resolve the ENTER
     *  row a closed position was opened by). Null when no such row exists. */
    public DecisionLog findBySignalIdAndAction(String signalId, String action) {
        return jdbc.sql("""
                SELECT * FROM decision_log
                WHERE signal_id = :signalId AND action = :action
                ORDER BY created_at DESC LIMIT 1
                """)
                .param("signalId", signalId)
                .param("action", action)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    /** All decision rows for a symbol with the given action, oldest first — used for the
     *  symbol+nearest-timestamp ENTER fallback join and for the reentry-within-10d whipsaw
     *  check. */
    public List<DecisionLog> findBySymbolAndAction(String symbol, String action) {
        return jdbc.sql("""
                SELECT * FROM decision_log
                WHERE symbol = :symbol AND action = :action
                ORDER BY created_at ASC
                """)
                .param("symbol", symbol)
                .param("action", action)
                .query(this::mapRow)
                .list();
    }

    /** Decision rows for a symbol whose action is one of {@code actions} and whose
     *  {@code created_at} falls within [{@code from}, {@code to}] inclusive, oldest first — used
     *  to assemble TRIM rows and the final EXIT_FULL/LOG_HARD_EXIT/RECONCILE_CLOSE row for a
     *  closed position's holding window. */
    public List<DecisionLog> findBySymbolAndActionsBetween(String symbol, List<String> actions,
            Instant from, Instant to) {
        return jdbc.sql("""
                SELECT * FROM decision_log
                WHERE symbol = :symbol AND action IN (:actions)
                  AND created_at >= :from AND created_at <= :to
                ORDER BY created_at ASC
                """)
                .param("symbol", symbol)
                .param("actions", actions)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(this::mapRow)
                .list();
    }

    /** Count of decision rows for a symbol with the given {@code reason_code}, regardless of
     *  action — used to rate-limit the {@code PENDING_EXIT_STALE} escalation to once per
     *  threshold crossing. Scoped by symbol+reason (decision_log has no {@code position_id}
     *  column, only an opaque {@code order_json} blob), so a same-symbol reentry whose new
     *  position also goes stale would be suppressed by an older row's escalation; acceptable
     *  for now given the rarity of this path (see task-4 fix report). */
    public int countBySymbolAndReasonCode(String symbol, String reasonCode) {
        return jdbc.sql("""
                SELECT count(*) FROM decision_log
                WHERE symbol = :symbol AND reason_code = :reasonCode
                """)
                .param("symbol", symbol)
                .param("reasonCode", reasonCode)
                .query(Integer.class)
                .single();
    }

    private DecisionLog mapRow(ResultSet rs, int n) throws SQLException {
        Object confidenceObj = rs.getObject("confidence_in_decision");
        Object createdAtObj = rs.getObject("created_at");
        return new DecisionLog(
                rs.getString("log_id"),
                rs.getString("run_id"),
                rs.getString("rule_version"),
                rs.getString("trigger_type"),
                rs.getString("signal_id"),
                rs.getString("source_agent"),
                rs.getString("source_agent_version"),
                rs.getString("symbol"),
                readJson(rs.getString("inputs_snapshot")),
                readJson(rs.getString("veto_results")),
                rs.getString("action"),
                rs.getString("reason_code"),
                readJson(rs.getString("order_json")),
                rs.getString("reasoning"),
                confidenceObj == null ? null : rs.getDouble("confidence_in_decision"),
                readJson(rs.getString("latency")),
                createdAtObj == null ? null : createdAtObj.toString());
    }

    private String writeJson(JsonNode node, String fallback) {
        try { return mapper.writeValueAsString(node == null ? mapper.readTree(fallback) : node); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize decision-log JSON", e); }
    }

    private String writeJsonOrNull(JsonNode node) {
        if (node == null) return null;
        try { return mapper.writeValueAsString(node); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize decision-log JSON", e); }
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
