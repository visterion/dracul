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

    /**
     * Inserts a leg only if {@code (position_id, tranche)} is not already taken, and reports
     * whether the row was actually written.
     *
     * <p>The plain {@link #insert} would abort the surrounding transaction on a duplicate, and the
     * callers that seed legs are all re-entrant by construction: reconcile runs on a schedule and
     * may see the same working stop on many consecutive passes, and a re-delivered webhook can
     * replay a placement. Making the duplicate a no-op at the SQL level — rather than a
     * read-then-insert in Java — is what keeps a concurrent second pass from slipping between the
     * check and the write and poisoning the transaction.
     *
     * <p>A leg that was CLOSED or CANCELLED also occupies its tranche, so it is never resurrected
     * here: the conflict target is the tranche, not the tranche-and-status.
     */
    public boolean insertIfAbsent(ExecutorPositionLeg leg) {
        String status = leg.status() != null ? leg.status() : ExecutorPositionLeg.OPEN;
        int rows = jdbc.sql("""
                INSERT INTO executor_position_leg
                  (position_id, tranche, entry_order_id, stop_order_id, qty, status,
                   exit_price, exit_reason, closed_at)
                VALUES (:positionId, :tranche, :entryOrderId, :stopOrderId, :qty, :status,
                        :exitPrice, :exitReason, CAST(:closedAt AS timestamptz))
                ON CONFLICT (position_id, tranche) DO NOTHING
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
                .update();
        return rows > 0;
    }

    /**
     * Repoints one leg's protective stop order id, for a leg the broker re-issued during a
     * flatten rollback. {@code newStopOrderId} may be null: an id the broker no longer reports as
     * live is dead, and a null column is a visible protection gap, whereas a stale id looks live
     * and fails the next ratchet run with LEG_NOT_FOUND — the same reasoning
     * {@code ExecutorPositionRepository.repointStopLegs} applies to the position columns.
     *
     * <p>Touches only the id. The leg's {@code qty} still means shares HELD and is converged from
     * the broker's own working stop by {@code ReconcileService.syncLegQuantities}, not from here.
     */
    public void repointLegStop(long legId, String newStopOrderId) {
        jdbc.sql("UPDATE executor_position_leg SET stop_order_id = :sid WHERE id = :id")
                .param("sid", newStopOrderId)
                .param("id", legId)
                .update();
    }

    /**
     * Marks every still-OPEN leg of a position CANCELLED — for an entry that was cancelled before
     * it ever filled, so no shares were ever held on it.
     *
     * <p>Distinct from {@link #closeLeg}: CLOSED means the shares left through a fill and carries
     * an exit price; CANCELLED means they were never acquired. Writing an exit price here would
     * invent a trade that did not happen, so none is written.
     */
    public int cancelOpenLegs(long positionId) {
        return jdbc.sql("""
                UPDATE executor_position_leg
                SET status = :cancelled
                WHERE position_id = :positionId AND status = :open
                """)
                .param("cancelled", ExecutorPositionLeg.CANCELLED)
                .param("open", ExecutorPositionLeg.OPEN)
                .param("positionId", positionId)
                .update();
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

    /** Converges a leg's quantity to the broker's own number. {@code qty} means shares HELD
     *  (see {@link ExecutorPositionLeg}), so a leg whose stop order the broker still works with a
     *  different size follows the broker. Never call this with a non-positive quantity: the table
     *  carries {@code CHECK (qty > 0)} and a leg that reaches zero must be CLOSED, not resized. */
    public void syncLegQty(long legId, BigDecimal brokerQty) {
        jdbc.sql("UPDATE executor_position_leg SET qty = :qty WHERE id = :id")
                .param("qty", brokerQty)
                .param("id", legId)
                .update();
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
