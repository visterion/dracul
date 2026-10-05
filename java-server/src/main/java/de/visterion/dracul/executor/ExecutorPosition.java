package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.util.List;

/**
 * One row of the executor position book.
 *
 * <p><b>{@code qty} means shares actually HELD at the broker, never shares intended.</b> Every
 * consumer sizes real money off it — {@code exit_position}'s flatten remainder, the exposure and
 * open-heat inputs to the BUDGET/HEAT_LIMIT vetos, the outcome book — so a value the broker does
 * not back over-sizes each of them. Consequently a submitted-but-unfilled order (a working GTD
 * entry, a working tranche-2 limit) contributes NOTHING here until the broker reports the shares;
 * {@code ReconcileService.updateMaintenance} converges this field to the broker's reported
 * quantity on every pass, the same way it converges {@code entryPrice} to the broker basis.
 * Intended-but-unfilled size is derivable from the tranche order ids and belongs in
 * notifications/display only.
 *
 * <p><b>{@code stopOrderId}, {@code tranche2OrderId} and {@code tranche2StopOrderId} are not
 * legacy.</b> They are the only key that binds one of the broker's own tranches to a row here:
 * {@code ReconcileService.seedLegsFromWorkingStops} matches a working stop order against these
 * ids to create the corresponding {@code executor_position_leg} row in the first place, and
 * {@code repointStopLegs}/{@code repointLegStops} (in {@link ExecutorPositionRepository} and
 * {@code ExecutorWebhookController} respectively) keep both the columns and the leg rows pointed
 * at the same live order after a flatten rollback replaces it. {@code StopRatchetService} also
 * still reads {@code tranche()}, {@code tranche2OrderId} and {@code tranche2StopOrderId} to decide
 * {@code expectsTwoLegs} for positions that have no leg rows yet. Dropping these columns removes
 * the binding key and would silently strand every future leg-creation and repoint. They can only
 * be dropped once leg creation sources per-tranche ids from the broker's bracket structure
 * directly instead of from these columns.
 *
 * <p><b>{@code stopLegsCollapsed}</b> has exactly one job (BUG-S13): on the legless, column-based
 * fallback routing in {@code StopRatchetService.ratchetLegs} and the equivalent trim path in
 * {@code ReconcileService}, it distinguishes a two-tranche position that legitimately has only one
 * live stop leg (ratchet it as one) from one whose second id is merely not yet known (escalate).
 * It has exactly two genuine readers today — {@code StopRatchetService}'s {@code twoStopLegs} and
 * {@code ReconcileService}'s {@code collapsed} — and both live entirely inside that legless
 * fallback chain. It dies only when that fallback path itself is removed, which requires every
 * position to carry {@code executor_position_leg} rows before the fallback is ever reached; that
 * has not happened in this project and is not implied by anything built so far.
 */
public record ExecutorPosition(
        Long id,
        String connection,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal entryPrice,
        BigDecimal initialStop,
        BigDecimal activeStop,
        int tranche,
        BigDecimal rValue,
        List<String> killCriteria,
        String sourceSignalId,
        String sourceAgent,
        String entryDate,
        BigDecimal mfe,
        String status,
        String brokerOrderId,
        BigDecimal highestPrice,
        BigDecimal mfeR,
        int softConfirmCount,
        BigDecimal exitPrice,
        BigDecimal realizedR,
        String exitReason,
        String closedAt,
        String stopOrderId,
        String sector,
        BigDecimal entryDayHigh,
        String tranche2OrderId,
        String tranche2StopOrderId,
        int trimCount,
        BigDecimal lowestPrice,
        String entryExpiresAt,
        BigDecimal submittedLimitPrice,
        String pendingExitReason,
        String exitOrderId,
        BigDecimal pendingExitFillPrice,
        boolean stopLegsCollapsed,
        /** The price the protective leg actually rests at, which is buffered AWAY from
         *  {@code activeStop} (below it for a BUY, above it for a SELL) so an intraday wick cannot
         *  close a position the close-based rule would have kept. NULL for rows opened before V48
         *  until the next ratchet writes it; every consumer must fall back to {@code activeStop}.
         *  Never fed to a veto — heat stays on the logical risk. */
        BigDecimal brokerStop,
        /** Timestamp of the first reconcile pass that saw a broker holding for this position with
         *  no entry order still working. NULL means the entry is not (yet) filled, which makes the
         *  position ineligible for a second tranche. Written once, by
         *  {@code ReconcileService.reconcile}. */
        String entryFilledAt,
        /** The structured kill level the hard trigger enforces (V51): one daily close strictly
         *  below it on a BUY => HARD_KILL_CRITERIA. Null when the signal had none or place-entry
         *  dropped it. There is deliberately NO back-compat constructor: every copy site
         *  (ReconcileService rebuilds every OPEN row each run) must forward it explicitly. */
        BigDecimal killCloseBelow,
        /** Why place-entry did not arm the signal's level: {@code too_tight} or
         *  {@code breached_at_adoption}; null when nothing was dropped (V51). */
        String killCloseBelowDropped,
        /** Exit lifecycle of this position (V52), derived at place-entry from the signal's
         *  mechanism ({@link ExitProfile#fromMechanism}); STANDARD for every pre-V52 row and for
         *  rows without a signal. Every copy site must forward it: a CONVICTION row rebuilt as
         *  STANDARD gets the chandelier for one pass, and the monotonic ratchet guard then makes
         *  that pass permanent (R1 Minor 8). */
        ExitProfile exitProfile,
        /** The thesis-destroying event the nightly strigoi-tech check flagged (V52); non-null
         *  makes HardTriggerService flatten the position (HARD_CATASTROPHE). Never cleared by code. */
        String catastropheReason,
        /** When {@link #catastropheReason} was flagged (V52); null when not flagged. */
        String catastropheFlaggedAt,
        /** Broker order id of an accepted partial close whose fill price is not known yet (V52,
         *  spec §5.7). Written by PartialExitService, resolved by ReconcileService. */
        String pendingTrimOrderId,
        /** True while the protective leg rests TIGHTER than the logical stop because the broker
         *  rejects a bracket leg beyond its proximity band at entry (V52, spec §5.3). */
        boolean brokerStopNarrow,
        /** When strigoi-momentum committed this MOMENTUM position to a rebalance exit (V53, spec
         *  2026-10-04 §4); null = not flagged. HardTriggerService flattens a flagged row
         *  (HARD_REBALANCE); for a MOMENTUM signal it is excluded from MECHANISM_BUDGET, BUDGET and
         *  MAX_POSITIONS (capital being freed tonight). Every copy site must forward it. */
        String rebalanceExitAt) {

    /** {@link #exitProfile()}, never null: a record built without a profile is STANDARD, exactly
     *  like every pre-V52 row. Every capability check reads this, never the raw component. */
    public ExitProfile profile() {
        return exitProfile == null ? ExitProfile.STANDARD : exitProfile;
    }

    /** A MOMENTUM row the strigoi-momentum completion committed to a rebalance exit (V53): capital
     *  being freed tonight, excluded from a MOMENTUM signal's capacity (spec 2026-10-04 §3). */
    public boolean committedRebalanceExit() {
        return profile() == ExitProfile.MOMENTUM && rebalanceExitAt != null;
    }
}
