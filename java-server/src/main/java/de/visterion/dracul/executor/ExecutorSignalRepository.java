package de.visterion.dracul.executor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Persists injected {@link ExecutorSignal}s awaiting execution evaluation. */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ExecutorSignalRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutorSignalRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ExecutorSignalRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(ExecutorSignal s) {
        String status = s.status() != null ? s.status() : "PENDING";
        jdbc.sql("""
                INSERT INTO executor_signal
                  (signal_id, source, agent_version, symbol, direction, confidence, mechanism,
                   kill_criteria, horizon, reference_price, status, thesis, prey_id)
                VALUES (:signalId, :source, :agentVersion, :symbol, :direction, :confidence, :mechanism,
                        CAST(:killCriteria AS jsonb), :horizon, :referencePrice, :status,
                        CAST(:thesis AS jsonb), CAST(:preyId AS uuid))
                ON CONFLICT (signal_id) DO NOTHING
                """)
                .param("signalId", s.signalId())
                .param("source", s.source())
                .param("agentVersion", s.agentVersion())
                .param("symbol", s.symbol())
                .param("direction", s.direction())
                .param("confidence", s.confidence())
                .param("mechanism", s.mechanism())
                .param("killCriteria", writeJson(s.killCriteria()))
                .param("horizon", s.horizon())
                .param("referencePrice", s.referencePrice())
                .param("status", status)
                .param("thesis", writeThesis(s.thesis()))
                .param("preyId", s.preyId())
                .update();
    }

    public List<ExecutorSignal> findPending(int limit) {
        return jdbc.sql("""
                SELECT * FROM executor_signal WHERE status = 'PENDING'
                ORDER BY created_at ASC LIMIT :lim
                """)
                .param("lim", limit)
                .query(this::mapRow)
                .list();
    }

    public ExecutorSignal findById(String signalId) {
        return jdbc.sql("SELECT * FROM executor_signal WHERE signal_id = :signalId")
                .param("signalId", signalId)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    /** Run-id of the prey linked to this signal (Schicht 1 FK executor_signal.prey_id -> prey.id).
     *  Null when the signal is unknown, has no prey_id (operator inject / legacy), or prey.run_id is null. */
    public String findRunIdBySignalId(String signalId) {
        return jdbc.sql("""
                SELECT p.run_id
                FROM executor_signal es
                JOIN prey p ON p.id = es.prey_id
                WHERE es.signal_id = :signalId
                """)
                .param("signalId", signalId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public void markStatus(String signalId, String status) {
        jdbc.sql("""
                UPDATE executor_signal SET status = :status, processed_at = now()
                WHERE signal_id = :signalId
                """)
                .param("status", status)
                .param("signalId", signalId)
                .update();
    }

    private ExecutorSignal mapRow(ResultSet rs, int n) throws SQLException {
        Object confidenceObj = rs.getObject("confidence");
        BigDecimal referencePrice = rs.getBigDecimal("reference_price");
        Object createdAtObj = rs.getObject("created_at");
        return new ExecutorSignal(
                rs.getString("signal_id"),
                rs.getString("source"),
                rs.getString("agent_version"),
                rs.getString("symbol"),
                rs.getString("direction"),
                confidenceObj == null ? null : rs.getDouble("confidence"),
                rs.getString("mechanism"),
                readList(rs.getString("kill_criteria")),
                rs.getString("horizon"),
                referencePrice,
                rs.getString("status"),
                createdAtObj == null ? null : createdAtObj.toString(),
                readThesis(rs.getString("thesis")),
                rs.getString("prey_id"));
    }

    private String writeJson(List<String> v) {
        try { return mapper.writeValueAsString(v == null ? List.of() : v); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize executor-signal killCriteria", e); }
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

    private String writeThesis(JsonNode node) {
        if (node == null) return null;                       // SQL NULL, never {} husk
        try { return mapper.writeValueAsString(node); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize executor-signal thesis", e); }
    }

    private JsonNode readThesis(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readTree(json); }
        catch (Exception e) { log.error("Failed to deserialize executor-signal thesis: {}", json, e); return null; }
    }
}
