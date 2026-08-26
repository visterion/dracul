package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.RestoredLeg;
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
                   pending_exit_fill_price, stop_legs_collapsed)
                VALUES (:connection, :symbol, :side, :qty, :entryPrice, :initialStop, :activeStop,
                        :tranche, :rValue, CAST(:killCriteria AS jsonb), :sourceSignalId, :sourceAgent,
                        :mfe, :status, :brokerOrderId, :highestPrice, :mfeR, :softConfirmCount,
                        :exitPrice, :realizedR, :exitReason, :stopOrderId, :sector, :entryDayHigh,
                        :tranche2OrderId, :tranche2StopOrderId, :trimCount, :lowestPrice,
                        CAST(:entryExpiresAt AS timestamptz), :submittedLimitPrice, :pendingExitReason,
                        :exitOrderId, :pendingExitFillPrice, :stopLegsCollapsed)
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
                .param("stopLegsCollapsed", p.stopLegsCollapsed())
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

    /** {@code rValue} is the denominator (risk-per-share) that {@code realizedR} was actually
     *  divided by — either the planned-risk denominator ({@code realizedRAgainstPlannedRisk}) or
     *  the live entry/stop denominator ({@code computeR}), whichever the caller used. Persisting
     *  it is what makes {@code realized_r} reconcilable against the other stored columns; without
     *  it, a row computed against planned risk looks inconsistent with entry/stop/exit. Null
     *  exactly when {@code realizedR} is null (zero/non-positive denominator — nothing meaningful
     *  to record). */
    public void close(long id, BigDecimal exitPrice, BigDecimal realizedR, String exitReason,
            BigDecimal rValue) {
        close(id, exitPrice, realizedR, exitReason, null, rValue);
    }

    public void close(long id, BigDecimal exitPrice, BigDecimal realizedR, String exitReason,
            String exitPriceSource, BigDecimal rValue) {
        jdbc.sql("""
                UPDATE executor_position
                SET status = 'CLOSED',
                    exit_price = :exitPrice,
                    realized_r = :realizedR,
                    r_value = :rValue,
                    exit_reason = :exitReason,
                    exit_price_source = :exitPriceSource,
                    closed_at = now()
                WHERE id = :id
                """)
                .param("exitPrice", exitPrice)
                .param("realizedR", realizedR)
                .param("rValue", rValue)
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

    /** Overwrites {@code qty} with the quantity the broker actually reports holding, leaving every
     *  other field untouched — the qty analogue of {@link #syncEntryPrice}. This is what makes
     *  {@code ExecutorPosition.qty} mean "shares held": a partially filled entry or a still-working
     *  tranche-2 limit converges here as the broker confirms shares, instead of the book carrying
     *  an intended size no position backs. */
    public void syncQty(long id, BigDecimal brokerQty) {
        jdbc.sql("UPDATE executor_position SET qty = :qty WHERE id = :id")
                .param("qty", brokerQty)
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

    /**
     * Records that a tranche-2 order is working: flips {@code tranche} to 2 and stores the two leg
     * ids.
     *
     * <p>{@code qty}/{@code entryPrice} are written through unchanged by the only production
     * caller ({@code ExecutorWebhookController.addTranche}) — a submitted tranche-2 limit is not
     * yet held, and {@code qty} means shares held (see {@link ExecutorPosition}). The book grows
     * when {@code ReconcileService} sees the broker's larger position, not when the order is sent.
     * The parameters stay on the signature because the columns must be written in the same
     * statement as the tranche flip.
     *
     * <p>{@code stop_legs_collapsed} is cleared here. Its one job is to explain why a two-tranche
     * position names only ONE stop leg; a position that has just been given a second stop leg
     * names two, so leaving the flag set would have the row assert a collapse its own columns
     * contradict — and {@link StopRatchetService} reads the pair to tell a legitimately
     * single-legged survivor from a book whose second id is merely unknown.
     */
    public void updateTranche2(long id, BigDecimal newQty, BigDecimal newEntryPrice,
                               String tranche2OrderId, String tranche2StopOrderId) {
        jdbc.sql("""
                UPDATE executor_position
                SET tranche = 2, qty = :qty, entry_price = :entryPrice,
                    tranche2_order_id = :t2o, tranche2_stop_order_id = :t2s,
                    stop_legs_collapsed = false
                WHERE id = :id
                """)
                .param("qty", newQty).param("entryPrice", newEntryPrice)
                .param("t2o", tranche2OrderId).param("t2s", tranche2StopOrderId)
                .param("id", id)
                .update();
    }

    /** Records a scale-out trim: shrinks {@code qty}, bumps {@code trim_count}, and resets the
     *  soft-confirm streak (a fresh qty level restarts trailing-stop soft confirmation).
     *
     *  <p>Kept for callers that have not yet been migrated to the leg-restore-aware overload
     *  ({@link #recordTrim(long, BigDecimal, int, List, boolean)}) — it does not touch the stop
     *  columns or the collapse flag. */
    public void recordTrim(long id, BigDecimal newQty, int newTrimCount) {
        jdbc.sql("UPDATE executor_position SET qty = :qty, trim_count = :tc, soft_confirm_count = 0 WHERE id = :id")
                .param("qty", newQty).param("tc", newTrimCount).param("id", id).update();
    }

    /**
     * Books a partial exit. Quantities come from the BROKER, never from our own arithmetic:
     * Dracul floors {@code qty × (1−fraction)} while Saxo floors {@code qty × fraction} and
     * subtracts, which differ by one share on four of five live positions.
     *
     * <p>The new stop leg ids are matched by the id each one replaces, so a two-tranche book keeps
     * its columns straight without guessing. A collapse (remainder smaller than the leg count)
     * nulls the second column and records the flag — the ratchet reads it to tell a legitimately
     * single-legged position from one whose second leg id is merely unknown.
     *
     * <p><b>A collapse still reconciles by {@code replaces}, never by list position/count.</b>
     * Agora's own contract (see {@code documentation/exit-tools.md}, "partial close restores
     * protective legs") is explicit that more than one leg can come back on a collapse — the
     * allocator fills greedily down tightness order, one share at a time, so e.g. three 1-share
     * stop legs with 2 remaining yields TWO restored legs of 1 share each, not one. Assuming
     * "exactly one" and taking {@code legs.get(0)} unconditionally is doubly wrong: it can drop a
     * second, genuinely live leg on the floor, and on an instrument carrying a THIRD, foreign
     * opposite-side stop (Agora filters {@code lookupRelatedOrders} by instrument alone, not by
     * which caller owns a leg) index 0 need not even be one of Dracul's own two legs. So this
     * matches each returned leg to whichever of {@code stop_order_id}/{@code
     * tranche2_stop_order_id} its {@code replaces} names, exactly like the non-collapsed branch
     * below — the only thing "collapsed" changes is that a column whose old id was never named as
     * a {@code replaces} target (the leg that did NOT survive the collapse) is cleared rather than
     * left pointing at a cancelled id.
     */
    public void recordTrim(long id, BigDecimal newQty, int newTrimCount,
                           List<RestoredLeg> legs, boolean collapsed) {
        ExecutorPosition current = findById(id);
        String stopOrderId = current == null ? null : current.stopOrderId();
        String tranche2StopOrderId = current == null ? null : current.tranche2StopOrderId();
        if (collapsed) {
            String oldStopOrderId = stopOrderId;
            String oldTranche2StopOrderId = tranche2StopOrderId;
            boolean stopMatched = false;
            boolean tranche2Matched = false;
            for (RestoredLeg leg : legs) {
                if (leg.replaces() != null && leg.replaces().equals(oldStopOrderId)) {
                    stopOrderId = leg.orderId();
                    stopMatched = true;
                }
                if (leg.replaces() != null && leg.replaces().equals(oldTranche2StopOrderId)) {
                    tranche2StopOrderId = leg.orderId();
                    tranche2Matched = true;
                }
            }
            if (!stopMatched) stopOrderId = null;
            if (!tranche2Matched) tranche2StopOrderId = null;
        } else {
            for (RestoredLeg leg : legs) {
                if (leg.replaces() != null && leg.replaces().equals(stopOrderId)) {
                    stopOrderId = leg.orderId();
                }
                if (leg.replaces() != null && leg.replaces().equals(tranche2StopOrderId)) {
                    tranche2StopOrderId = leg.orderId();
                }
            }
        }
        jdbc.sql("""
                UPDATE executor_position
                SET qty = :qty,
                    trim_count = :tc,
                    soft_confirm_count = 0,
                    stop_order_id = :stopOrderId,
                    tranche2_stop_order_id = :tranche2StopOrderId,
                    stop_legs_collapsed = :collapsed
                WHERE id = :id
                """)
                .param("qty", newQty)
                .param("tc", newTrimCount)
                .param("stopOrderId", stopOrderId)
                .param("tranche2StopOrderId", tranche2StopOrderId)
                .param("collapsed", collapsed)
                .param("id", id)
                .update();
    }

    /**
     * Nulls whichever stop-leg column names {@code stopOrderId}, for a leg the broker has already
     * filled. Touches nothing else — no qty, no trim count, no soft-confirm streak.
     *
     * <p>A stale id is worse than a null one: {@link StopRatchetService} addresses the leg by name
     * and gets LEG_NOT_FOUND back, so the position spends a whole maintenance cycle in an
     * escalation caused by our own trim. A null column is a visible protection gap instead —
     * the same reasoning {@link #repointStopLegs} already applies to unmatched ids.
     *
     * <p>Written as a conditional UPDATE rather than read-modify-write so it cannot race another
     * writer between the SELECT and the UPDATE. A {@code stopOrderId} that matches neither column
     * is a no-op.
     */
    public void clearStopLeg(long id, String stopOrderId) {
        if (stopOrderId == null) return;
        jdbc.sql("""
                UPDATE executor_position
                SET stop_order_id = CASE WHEN stop_order_id = :sid THEN NULL ELSE stop_order_id END,
                    tranche2_stop_order_id = CASE WHEN tranche2_stop_order_id = :sid
                                                  THEN NULL ELSE tranche2_stop_order_id END
                WHERE id = :id
                """)
                .param("sid", stopOrderId)
                .param("id", id)
                .update();
    }

    /**
     * Records that this position's two stop legs have collapsed to one, without touching anything
     * else. For a trim that removed the tranche whose stop had already filled: {@link
     * #clearStopLeg} nulls the dead id, and this explains the null.
     *
     * <p>{@code stop_legs_collapsed} has exactly one job (BUG-S13): it tells {@link
     * StopRatchetService} why a two-tranche position names only one stop leg, and so separates a
     * legitimately single-legged survivor (ratchet the one leg) from a book whose second id is
     * merely unknown (escalate {@code TRANCHE_RATCHET_UNSUPPORTED}). Without it, a reconcile trim
     * would leave a survivor in the second state — an escalation on every maintenance run, caused
     * by our own bookkeeping. Set it only when fewer than two stop legs are actually named; the
     * flag must never be used to decide how MANY legs there are.
     */
    public void markStopLegsCollapsed(long id) {
        jdbc.sql("UPDATE executor_position SET stop_legs_collapsed = true WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * Repoints ONLY the stop-leg id columns, for a rejected trim whose rollback still changed
     * broker state (new leg ids, or a leg irrecoverably cancelled). Unlike {@link
     * #recordTrim(long, BigDecimal, int, List, boolean)}, this must never touch {@code qty},
     * {@code trim_count} or {@code soft_confirm_count} — no trim happened on this path, so the
     * soft-confirm ladder and trim ladder must survive untouched (a reset here would push a
     * retry roughly two maintenance runs out for no reason).
     *
     * <p>A currently-recorded stop-leg id that is NOT named as a {@code replaces} target in
     * {@code legs} is nulled, not left alone: Agora's own rollback can break at the first failure
     * and report fewer live legs than were cancelled (the LEG_RESTORE_FAILED_UNPROTECTED case),
     * so an unmatched id is dead, not merely stale. A null column is a visible protection gap; a
     * stale id looks live and fails LEG_NOT_FOUND on the next ratchet run instead.
     */
    public void repointStopLegs(long id, List<RestoredLeg> legs) {
        ExecutorPosition current = findById(id);
        if (current == null) return;

        String stopOrderId = current.stopOrderId();
        String tranche2StopOrderId = current.tranche2StopOrderId();
        boolean stopMatched = false;
        boolean tranche2Matched = false;
        for (RestoredLeg leg : legs) {
            if (leg.replaces() != null && leg.replaces().equals(current.stopOrderId())) {
                stopOrderId = leg.orderId();
                stopMatched = true;
            }
            if (leg.replaces() != null && leg.replaces().equals(current.tranche2StopOrderId())) {
                tranche2StopOrderId = leg.orderId();
                tranche2Matched = true;
            }
        }
        if (!stopMatched && current.stopOrderId() != null) {
            stopOrderId = null;
        }
        if (!tranche2Matched && current.tranche2StopOrderId() != null) {
            tranche2StopOrderId = null;
        }

        jdbc.sql("""
                UPDATE executor_position
                SET stop_order_id = :stopOrderId,
                    tranche2_stop_order_id = :tranche2StopOrderId
                WHERE id = :id
                """)
                .param("stopOrderId", stopOrderId)
                .param("tranche2StopOrderId", tranche2StopOrderId)
                .param("id", id)
                .update();
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

    /** Looks up a position by the broker's order id — the join key used to enrich executor
     *  positions with broker-side fill/order data (depot history). Returns {@code null} if none. */
    public ExecutorPosition findByBrokerOrderId(String brokerOrderId) {
        return jdbc.sql("SELECT * FROM executor_position WHERE broker_order_id = :bid ORDER BY entry_date DESC LIMIT 1")
                .param("bid", brokerOrderId)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    /** Looks up a position by its originating signal id — most-recent match by
     *  {@code entry_date}, used to join executor positions against Saxo history rows carrying
     *  the same {@code source_signal_id}. Returns {@code null} if none. */
    public ExecutorPosition findBySourceSignalId(String signalId) {
        return jdbc.sql("SELECT * FROM executor_position WHERE source_signal_id = :sig ORDER BY entry_date DESC LIMIT 1")
                .param("sig", signalId)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    /** The open executor position for this connection+symbol, or {@code null}. Heuristic link
     *  basis for the open-position transcript drilldown (open broker positions carry no
     *  clientRef/order id) — at most one OPEN row exists per (connection, symbol) by the
     *  {@code secondOpenRowForSameConnectionSymbolFails} DB constraint, so "most recent" is
     *  purely defensive here. */
    public ExecutorPosition findOpenBySymbol(String connection, String symbol) {
        return jdbc.sql("""
                SELECT * FROM executor_position
                WHERE status = 'OPEN' AND connection = :conn AND symbol = :symbol
                ORDER BY entry_date DESC LIMIT 1
                """)
                .param("conn", connection)
                .param("symbol", symbol)
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
                rs.getBigDecimal("pending_exit_fill_price"),
                rs.getBoolean("stop_legs_collapsed"));
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
