package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Test-only construction helpers for {@link ExecutorPosition}.
 *
 * <p>The record deliberately has NO back-compat constructor, so every production copy site is
 * forced by the compiler to carry {@code killCloseBelow} / {@code killCloseBelowDropped}. Tests
 * that predate the two components build their fixtures through {@link #withoutKillLevel}, which
 * takes the 39 pre-V51 components in their original order and sets both new ones to null.
 * Tests that need a level use {@link #withKillLevel} on top of any fixture.
 */
public final class ExecutorPositionFixtures {

    private ExecutorPositionFixtures() {}

    /** The 39 pre-V51 components, in record order; {@code killCloseBelow} and
     *  {@code killCloseBelowDropped} are null. */
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
                stopLegsCollapsed, brokerStop, entryFilledAt, null, null);
    }

    /** Copy of {@code p} with the two kill-level components replaced, everything else kept. */
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
                killCloseBelow, killCloseBelowDropped);
    }
}
