package de.visterion.dracul.executor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Persists the executor position book. */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ExecutorPositionRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutorPositionRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ExecutorPositionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long insert(ExecutorPosition p) {
        String status = p.status() != null ? p.status() : "OPEN";
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO executor_position
                  (connection, symbol, side, qty, entry_price, initial_stop, active_stop,
                   tranche, r_value, kill_criteria, source_signal_id, source_agent, mfe, status,
                   broker_order_id)
                VALUES (:connection, :symbol, :side, :qty, :entryPrice, :initialStop, :activeStop,
                        :tranche, :rValue, CAST(:killCriteria AS jsonb), :sourceSignalId, :sourceAgent,
                        :mfe, :status, :brokerOrderId)
                """)
                .param("connection", p.connection())
                .param("symbol", p.symbol())
                .param("side", p.side())
                .param("qty", p.qty())
                .param("entryPrice", p.entryPrice())
                .param("initialStop", p.initialStop())
                .param("activeStop", p.activeStop())
                .param("tranche", p.tranche())
                .param("rValue", p.rValue())
                .param("killCriteria", writeJson(p.killCriteria()))
                .param("sourceSignalId", p.sourceSignalId())
                .param("sourceAgent", p.sourceAgent())
                .param("mfe", p.mfe())
                .param("status", status)
                .param("brokerOrderId", p.brokerOrderId())
                .update(keyHolder, "id");
        return ((Number) keyHolder.getKeys().get("id")).longValue();
    }

    public List<ExecutorPosition> findOpen() {
        return jdbc.sql("""
                SELECT * FROM executor_position WHERE status = 'OPEN'
                ORDER BY entry_date DESC
                """)
                .query(this::mapRow)
                .list();
    }

    public int countOpen() {
        return jdbc.sql("SELECT count(*) FROM executor_position WHERE status = 'OPEN'")
                .query(Integer.class)
                .single();
    }

    private ExecutorPosition mapRow(ResultSet rs, int n) throws SQLException {
        Object entryDateObj = rs.getObject("entry_date");
        return new ExecutorPosition(
                rs.getLong("id"),
                rs.getString("connection"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("entry_price"),
                rs.getBigDecimal("initial_stop"),
                rs.getBigDecimal("active_stop"),
                rs.getInt("tranche"),
                rs.getBigDecimal("r_value"),
                readList(rs.getString("kill_criteria")),
                rs.getString("source_signal_id"),
                rs.getString("source_agent"),
                entryDateObj == null ? null : entryDateObj.toString(),
                rs.getBigDecimal("mfe"),
                rs.getString("status"),
                rs.getString("broker_order_id"));
    }

    private String writeJson(List<String> v) {
        try { return mapper.writeValueAsString(v == null ? List.of() : v); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize executor-position killCriteria", e); }
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
