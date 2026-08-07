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
        boolean stopLegsCollapsed) {
}
