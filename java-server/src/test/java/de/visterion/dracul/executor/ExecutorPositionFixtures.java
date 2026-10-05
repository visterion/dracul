package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Test-only construction helpers for {@link ExecutorPosition}.
 *
 * <p>The record deliberately has NO back-compat constructor, so every production copy site is
 * forced by the compiler to carry the V51 kill-level, V52 exit-profile and V53 rebalance
 * components. Tests that predate them build their fixtures through {@link #withoutKillLevel},
 * which takes the 39 pre-V51 components in their original order and defaults everything newer:
 * no kill level, {@link ExitProfile#STANDARD}, no catastrophe flag, no pending trim, broker leg
 * not narrow, no rebalance exit — exactly the state of a pre-V52 row.
 */
public final class ExecutorPositionFixtures {

    private ExecutorPositionFixtures() {}

    /** The 39 pre-V51 components, in record order; V51 + V52 + V53 components defaulted. */
    public static ExecutorPosition withoutKillLevel(Long id, String connection, String symbol,
            String side, BigDecimal qty, BigDecimal entryPrice, BigDecimal initialStop,
            BigDecimal activeStop, int tranche, BigDecimal rValue, List<String> killCriteria,
            String sourceSignalId, String sourceAgent, String entryDate, BigDecimal mfe,
            String status, String brokerOrderId, BigDecimal highestPrice, BigDecimal mfeR,
            int softConfirmCount, BigDecimal exitPrice, BigDecimal realizedR, String exitReason,
            String closedAt, String stopOrderId, String sector, BigDecimal entryDayHigh,
            String tranche2OrderId, String tranche2StopOrderId, int trimCount,
            BigDecimal lowestPrice, String entryExpiresAt, BigDecimal submittedLimitPrice,
            String pendingExitReason, String exitOrderId, BigDecimal pendingExitFillPrice,
            boolean stopLegsCollapsed, BigDecimal brokerStop, String entryFilledAt) {
        return new ExecutorPosition(id, connection, symbol, side, qty, entryPrice, initialStop,
                activeStop, tranche, rValue, killCriteria, sourceSignalId, sourceAgent, entryDate,
                mfe, status, brokerOrderId, highestPrice, mfeR, softConfirmCount, exitPrice,
                realizedR, exitReason, closedAt, stopOrderId, sector, entryDayHigh,
                tranche2OrderId, tranche2StopOrderId, trimCount, lowestPrice, entryExpiresAt,
                submittedLimitPrice, pendingExitReason, exitOrderId, pendingExitFillPrice,
                stopLegsCollapsed, brokerStop, entryFilledAt, null, null,
                ExitProfile.STANDARD, null, null, null, false, null);
    }

    /** Copy of {@code p} with the two V51 kill-level components replaced, everything else kept. */
    public static ExecutorPosition withKillLevel(ExecutorPosition p, BigDecimal killCloseBelow,
            String killCloseBelowDropped) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), p.brokerStop(), p.entryFilledAt(),
                killCloseBelow, killCloseBelowDropped,
                p.exitProfile(), p.catastropheReason(), p.catastropheFlaggedAt(),
                p.pendingTrimOrderId(), p.brokerStopNarrow(), p.rebalanceExitAt());
    }

    /** Copy of {@code p} with the five V52 components replaced, everything else kept. */
    public static ExecutorPosition withProfileFields(ExecutorPosition p, ExitProfile exitProfile,
            String catastropheReason, String catastropheFlaggedAt, String pendingTrimOrderId,
            boolean brokerStopNarrow) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), p.brokerStop(), p.entryFilledAt(),
                p.killCloseBelow(), p.killCloseBelowDropped(),
                exitProfile, catastropheReason, catastropheFlaggedAt, pendingTrimOrderId,
                brokerStopNarrow, p.rebalanceExitAt());
    }

    /** Copy of {@code p} with the V53 rebalance flag replaced, everything else kept. */
    public static ExecutorPosition withRebalanceExitAt(ExecutorPosition p, String rebalanceExitAt) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), p.brokerStop(), p.entryFilledAt(),
                p.killCloseBelow(), p.killCloseBelowDropped(),
                p.exitProfile(), p.catastropheReason(), p.catastropheFlaggedAt(),
                p.pendingTrimOrderId(), p.brokerStopNarrow(), rebalanceExitAt);
    }

    /** Copy of {@code p} with a pending-exit marker stamped under {@code reason}, everything else
     *  kept — a row whose flatten is already in flight. */
    public static ExecutorPosition withPendingExit(ExecutorPosition p, String reason) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                reason, "close-" + p.id(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), p.brokerStop(), p.entryFilledAt(),
                p.killCloseBelow(), p.killCloseBelowDropped(),
                p.exitProfile(), p.catastropheReason(), p.catastropheFlaggedAt(),
                p.pendingTrimOrderId(), p.brokerStopNarrow(), p.rebalanceExitAt());
    }

    /** Copy of {@code p} as a plain CONVICTION row: no flag, no pending trim, leg not narrow. */
    public static ExecutorPosition conviction(ExecutorPosition p) {
        return withProfileFields(p, ExitProfile.CONVICTION, null, null, null, false);
    }

    /** Copy of {@code p} as a plain MOMENTUM row: leg not narrow, no rebalance flag. */
    public static ExecutorPosition momentum(ExecutorPosition p) {
        return withRebalanceExitAt(
                withProfileFields(p, ExitProfile.MOMENTUM, null, null, null, false), null);
    }
}
