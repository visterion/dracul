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
import java.time.Instant;
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
                   broker_order_id, highest_price, mfe_r, soft_confirm_count, exit_price,
                   realized_r, exit_reason, stop_order_id, sector, entry_day_high,
                   tranche2_order_id, tranche2_stop_order_id, trim_count, lowest_price,
                   entry_expires_at, submitted_limit_price, pending_exit_reason, exit_order_id,
                   pending_exit_fill_price)
                VALUES (:connection, :symbol, :side, :qty, :entryPrice, :initialStop, :activeStop,
                        :tranche, :rValue, CAST(:killCriteria AS jsonb), :sourceSignalId, :sourceAgent,
                        :mfe, :status, :brokerOrderId, :highestPrice, :mfeR, :softConfirmCount,
                        :exitPrice, :realizedR, :exitReason, :stopOrderId, :sector, :entryDayHigh,
                        :tranche2OrderId, :tranche2StopOrderId, :trimCount, :lowestPrice,
                        CAST(:entryExpiresAt AS timestamptz), :submittedLimitPrice, :pendingExitReason,
                        :exitOrderId, :pendingExitFillPrice)
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
                .param("highestPrice", p.highestPrice())
                .param("mfeR", p.mfeR())
                .param("softConfirmCount", p.softConfirmCount())
                .param("exitPrice", p.exitPrice())
                .param("realizedR", p.realizedR())
                .param("exitReason", p.exitReason())
                .param("stopOrderId", p.stopOrderId())
                .param("sector", p.sector())
                .param("entryDayHigh", p.entryDayHigh())
                .param("tranche2OrderId", p.tranche2OrderId())
                .param("tranche2StopOrderId", p.tranche2StopOrderId())
                .param("trimCount", p.trimCount())
                .param("lowestPrice", p.lowestPrice())
                .param("entryExpiresAt", p.entryExpiresAt())
                .param("submittedLimitPrice", p.submittedLimitPrice())
                .param("pendingExitReason", p.pendingExitReason())
                .param("exitOrderId", p.exitOrderId())
                .param("pendingExitFillPrice", p.pendingExitFillPrice())
                .update(keyHolder, "id");
        return ((Number) keyHolder.getKeys().get("id")).longValue();
    }

    public void updateMaintenance(long id, BigDecimal highestPrice, BigDecimal mfeR,
            int softConfirmCount, BigDecimal activeStop, String stopOrderId) {
        jdbc.sql("""
                UPDATE executor_position
                SET highest_price = :highestPrice,
                    mfe_r = :mfeR,
                    soft_confirm_count = :softConfirmCount,
                    active_stop = :activeStop,
                    stop_order_id = COALESCE(:stopOrderId, stop_order_id)
                WHERE id = :id
                """)
                .param("highestPrice", highestPrice)
                .param("mfeR", mfeR)
                .param("softConfirmCount", softConfirmCount)
                .param("activeStop", activeStop)
                .param("stopOrderId", stopOrderId)
                .param("id", id)
                .update();
    }

    public void close(long id, BigDecimal exitPrice, BigDecimal realizedR, String exitReason) {
        close(id, exitPrice, realizedR, exitReason, null);
    }

    public void close(long id, BigDecimal exitPrice, BigDecimal realizedR, String exitReason,
            String exitPriceSource) {
        jdbc.sql("""
                UPDATE executor_position
                SET status = 'CLOSED',
                    exit_price = :exitPrice,
                    realized_r = :realizedR,
                    exit_reason = :exitReason,
                    exit_price_source = :exitPriceSource,
                    closed_at = now()
                WHERE id = :id
                """)
                .param("exitPrice", exitPrice)
                .param("realizedR", realizedR)
                .param("exitReason", exitReason)
                .param("exitPriceSource", exitPriceSource)
                .param("id", id)
                .update();
    }

    /** Overwrites {@code entry_price} with the broker's actual average fill price, leaving all
     *  other fields (stops, R-value, status) untouched — used to reconcile the book against the
     *  broker's fill report without disturbing derived risk figures. */
    public void syncEntryPrice(long id, BigDecimal brokerAvgEntryPrice) {
        jdbc.sql("UPDATE executor_position SET entry_price = :entryPrice WHERE id = :id")
                .param("entryPrice", brokerAvgEntryPrice)
                .param("id", id)
                .update();
    }

    /** Stamps a submitted-but-not-yet-confirmed exit onto an OPEN position (status stays OPEN
     *  until the fill is confirmed and {@link #close} is called). */
    public void markPendingExit(long id, String reason, String exitOrderId, BigDecimal fillPrice,
            Instant submittedAt) {
        jdbc.sql("""
                UPDATE executor_position
                SET pending_exit_reason = :reason,
                    exit_order_id = :exitOrderId,
                    pending_exit_fill_price = :fillPrice,
                    exit_submitted_at = :submittedAt
                WHERE id = :id
                """)
                .param("reason", reason)
                .param("exitOrderId", exitOrderId)
                .param("fillPrice", fillPrice)
                .param("submittedAt", java.sql.Timestamp.from(submittedAt))
                .param("id", id)
                .update();
    }

    /** Returns the {@code exit_submitted_at} timestamp stamped by {@link #markPendingExit} for a
     *  pending-exit row, or {@code null} if never stamped (or the row has no such column value).
     *  Not an {@link ExecutorPosition} record component — {@code ReconcileService} needs this only
     *  to age-gate the {@code PENDING_EXIT_STALE} escalation, so a dedicated lookup is simpler
     *  than widening the record for one consumer. */
    public Instant exitSubmittedAt(long id) {
        return jdbc.sql("SELECT exit_submitted_at FROM executor_position WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> {
                    java.sql.Timestamp ts = rs.getTimestamp("exit_submitted_at");
                    return ts == null ? null : ts.toInstant();
                })
                .optional()
                .orElse(null);
    }

    public void updateTranche2(long id, BigDecimal newQty, BigDecimal newEntryPrice,
                               String tranche2OrderId, String tranche2StopOrderId) {
        jdbc.sql("""
                UPDATE executor_position
                SET tranche = 2, qty = :qty, entry_price = :entryPrice,
                    tranche2_order_id = :t2o, tranche2_stop_order_id = :t2s
                WHERE id = :id
                """)
                .param("qty", newQty).param("entryPrice", newEntryPrice)
                .param("t2o", tranche2OrderId).param("t2s", tranche2StopOrderId)
                .param("id", id)
                .update();
    }

    /** Records a scale-out trim: shrinks {@code qty}, bumps {@code trim_count}, and resets the
     *  soft-confirm streak (a fresh qty level restarts trailing-stop soft confirmation). */
    public void recordTrim(long id, BigDecimal newQty, int newTrimCount) {
        jdbc.sql("UPDATE executor_position SET qty = :qty, trim_count = :tc, soft_confirm_count = 0 WHERE id = :id")
                .param("qty", newQty).param("tc", newTrimCount).param("id", id).update();
    }

    /** Persists the adverse-excursion extreme (lowest price seen while the position is open),
     *  used for MAE (max adverse excursion) tracking. */
    public void updateAdverseExtreme(long id, BigDecimal lowestPrice) {
        jdbc.sql("UPDATE executor_position SET lowest_price = :lp WHERE id = :id")
                .param("lp", lowestPrice).param("id", id).update();
    }

    /** Sets the good-till-date expiry for an unfilled entry order. */
    public void setEntryExpiresAt(long id, Instant expiresAt) {
        jdbc.sql("UPDATE executor_position SET entry_expires_at = :ts WHERE id = :id")
                .param("ts", java.sql.Timestamp.from(expiresAt)).param("id", id).update();
    }

    /** Cancels a position whose GTD entry expired unfilled (Task 6, {@code EntryExpiryService}) —
     *  never a fill, so no exit price/realized R applies here (unlike {@link #close}). */
    public void markCancelled(long id) {
        jdbc.sql("UPDATE executor_position SET status = 'CANCELLED', closed_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    /** Clears the GTD expiry after {@code EntryExpiryService} has processed the position, making
     *  the expiry one-shot by construction: {@link #findOpenUnfilledPastExpiry} filters on
     *  {@code entry_expires_at IS NOT NULL}, so a cleared row can never be re-processed (e.g. a
     *  partially-filled entry whose remainder was already cancelled). */
    public void clearEntryExpiry(long id) {
        jdbc.sql("UPDATE executor_position SET entry_expires_at = NULL WHERE id = :id")
                .param("id", id).update();
    }

    /** Open positions whose entry GTD expiry has passed. Fill detection is a separate concern
     *  (Task 6) — this only filters by expiry. */
    public List<ExecutorPosition> findOpenUnfilledPastExpiry(Instant now) {
        return jdbc.sql("SELECT * FROM executor_position WHERE status = 'OPEN' AND entry_expires_at IS NOT NULL AND entry_expires_at < :now")
                .param("now", java.sql.Timestamp.from(now))
                .query(this::mapRow)
                .list();
    }

    public ExecutorPosition findById(long id) {
        return jdbc.sql("SELECT * FROM executor_position WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    public List<ExecutorPosition> findOpen() {
        return jdbc.sql("""
                SELECT * FROM executor_position WHERE status = 'OPEN'
                ORDER BY entry_date DESC
                """)
                .query(this::mapRow)
                .list();
    }

    /** Closed positions (final exit already recorded), oldest first — feeds the outcome batch
     *  job (Task 9), which computes realized-R/MAE/whipsaw once a position is done moving. */
    public List<ExecutorPosition> findClosed() {
        return jdbc.sql("""
                SELECT * FROM executor_position WHERE status = 'CLOSED'
                ORDER BY closed_at ASC
                """)
                .query(this::mapRow)
                .list();
    }

    public int countOpen() {
        return jdbc.sql("SELECT count(*) FROM executor_position WHERE status = 'OPEN'")
                .query(Integer.class)
                .single();
    }

    /** Count positions ENTERED (entry_date) at or after {@code since}, regardless of current
     *  status — used for weekly-pace limits (a stopped-out position still counted toward pace). */
    public int countEnteredSince(java.time.Instant since) {
        return jdbc.sql("SELECT count(*) FROM executor_position WHERE entry_date >= :since")
                .param("since", java.sql.Timestamp.from(since))
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
                rs.getString("broker_order_id"),
                rs.getBigDecimal("highest_price"),
                rs.getBigDecimal("mfe_r"),
                rs.getInt("soft_confirm_count"),
                rs.getBigDecimal("exit_price"),
                rs.getBigDecimal("realized_r"),
                rs.getString("exit_reason"),
                closedAtOrNull(rs),
                rs.getString("stop_order_id"),
                rs.getString("sector"),
                rs.getBigDecimal("entry_day_high"),
                rs.getString("tranche2_order_id"),
                rs.getString("tranche2_stop_order_id"),
                rs.getInt("trim_count"),
                rs.getBigDecimal("lowest_price"),
                entryExpiresAtOrNull(rs),
                rs.getBigDecimal("submitted_limit_price"),
                rs.getString("pending_exit_reason"),
                rs.getString("exit_order_id"),
                rs.getBigDecimal("pending_exit_fill_price"));
    }

    private String entryExpiresAtOrNull(ResultSet rs) throws SQLException {
        Object entryExpiresAtObj = rs.getObject("entry_expires_at");
        return entryExpiresAtObj == null ? null : entryExpiresAtObj.toString();
    }

    private String closedAtOrNull(ResultSet rs) throws SQLException {
        Object closedAtObj = rs.getObject("closed_at");
        return closedAtObj == null ? null : closedAtObj.toString();
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
