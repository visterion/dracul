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
                   broker_order_id, run_id)
                VALUES (:signalId, :symbol, :accepted, :rejectReason, CAST(:vetoTrace AS jsonb), :rationale,
                        :brokerOrderId, :runId)
                """)
                .param("signalId", d.signalId())
                .param("symbol", d.symbol())
                .param("accepted", d.accepted())
                .param("rejectReason", d.rejectReason())
                .param("vetoTrace", writeJson(d.vetoTrace()))
                .param("rationale", d.rationale())
                .param("brokerOrderId", d.brokerOrderId())
                .param("runId", d.runId())
                .update(keyHolder, "id");
        return ((Number) keyHolder.getKeys().get("id")).longValue();
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
                createdAtObj == null ? null : createdAtObj.toString());
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
