package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Persists the per-tranche legs of the executor position book (see {@link ExecutorPositionLeg}). */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ExecutorPositionLegRepository {

    private final JdbcClient jdbc;

    public ExecutorPositionLegRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(ExecutorPositionLeg leg) {
        String status = leg.status() != null ? leg.status() : ExecutorPositionLeg.OPEN;
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO executor_position_leg
                  (position_id, tranche, entry_order_id, stop_order_id, qty, status,
                   exit_price, exit_reason, closed_at)
                VALUES (:positionId, :tranche, :entryOrderId, :stopOrderId, :qty, :status,
                        :exitPrice, :exitReason, CAST(:closedAt AS timestamptz))
                """)
                .param("positionId", leg.positionId())
                .param("tranche", leg.tranche())
                .param("entryOrderId", leg.entryOrderId())
                .param("stopOrderId", leg.stopOrderId())
                .param("qty", leg.qty())
                .param("status", status)
                .param("exitPrice", leg.exitPrice())
                .param("exitReason", leg.exitReason())
                .param("closedAt", leg.closedAt())
                .update(keyHolder, "id");
        return ((Number) keyHolder.getKeys().get("id")).longValue();
    }

    public List<ExecutorPositionLeg> findByPosition(long positionId) {
        return jdbc.sql("""
                SELECT * FROM executor_position_leg
                WHERE position_id = :positionId
                ORDER BY tranche
                """)
                .param("positionId", positionId)
                .query(this::mapRow)
                .list();
    }

    public List<ExecutorPositionLeg> findOpenByPosition(long positionId) {
        return jdbc.sql("""
                SELECT * FROM executor_position_leg
                WHERE position_id = :positionId AND status = :status
                ORDER BY tranche
                """)
                .param("positionId", positionId)
                .param("status", ExecutorPositionLeg.OPEN)
                .query(this::mapRow)
                .list();
    }

    public void closeLeg(long legId, BigDecimal exitPrice, String exitReason, Instant closedAt) {
        jdbc.sql("""
                UPDATE executor_position_leg
                SET status = :status,
                    exit_price = :exitPrice,
                    exit_reason = :exitReason,
                    closed_at = :closedAt
                WHERE id = :id
                """)
                .param("status", ExecutorPositionLeg.CLOSED)
                .param("exitPrice", exitPrice)
                .param("exitReason", exitReason)
                .param("closedAt", closedAt == null ? null : Timestamp.from(closedAt))
                .param("id", legId)
                .update();
    }

    private ExecutorPositionLeg mapRow(ResultSet rs, int n) throws SQLException {
        return new ExecutorPositionLeg(
                rs.getLong("id"),
                rs.getLong("position_id"),
                rs.getInt("tranche"),
                rs.getString("entry_order_id"),
                rs.getString("stop_order_id"),
                rs.getBigDecimal("qty"),
                rs.getString("status"),
                rs.getBigDecimal("exit_price"),
                rs.getString("exit_reason"),
                closedAtOrNull(rs));
    }

    private String closedAtOrNull(ResultSet rs) throws SQLException {
        Object closedAtObj = rs.getObject("closed_at");
        return closedAtObj == null ? null : closedAtObj.toString();
    }
}
