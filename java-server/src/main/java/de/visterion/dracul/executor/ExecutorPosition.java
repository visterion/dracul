package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.util.List;

/** One row of the executor position book. */
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
        String stopOrderId) {
}
