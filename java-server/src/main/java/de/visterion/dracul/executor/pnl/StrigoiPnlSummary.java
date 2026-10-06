package de.visterion.dracul.executor.pnl;

import java.math.BigDecimal;

/**
 * Per-strigoi result (spec 2026-10-06). EUR sums skip null trade amounts — {@code flaggedTrades}
 * counts the trades that carry a flag, so the UI can say the sum is incomplete. {@code hitRate}
 * = wins / closedTrades (4 decimals), null with no closed trade; a 0 result is neither win nor
 * loss. {@code sumR} = Σ {@code realized_r} of CLOSED trades.
 */
public record StrigoiPnlSummary(String strigoi, int closedTrades, int wins, int losses,
        BigDecimal hitRate, BigDecimal realizedEur, BigDecimal unrealizedEur, BigDecimal totalEur,
        BigDecimal sumR, int openPositions, BigDecimal openCostEur, int flaggedTrades) {
}
