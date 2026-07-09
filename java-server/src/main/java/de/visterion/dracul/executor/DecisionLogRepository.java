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
