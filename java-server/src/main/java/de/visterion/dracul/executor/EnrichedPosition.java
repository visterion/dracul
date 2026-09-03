package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.util.List;

/** A maintained open position, enriched with the current market/derived state used to render
 *  the Chronicle position book and to drive the next maintenance pass. */
public record EnrichedPosition(
        long id,
        String connection,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal entryPrice,
        BigDecimal activeStop,
        BigDecimal currentPrice,
        BigDecimal atr,
        BigDecimal chandelierLevel,
        BigDecimal rCurrent,
        BigDecimal mfeR,
        long daysHeld,
        List<String> killCriteria,
        List<String> killCriteriaBreached,
        boolean chandelierBreach,
        boolean maBreak,
        int softConfirmCount,
        boolean tranche2Eligible,
        String tranche2Reason,
        String sourceSignalId,
        int trimCount,
        double suggestedFraction,
        /** False while the position's GTD entry has no confirmed fill (no broker holdings):
         *  hard triggers, ratcheting, soft-confirm accumulation and LLM exits are all gated
         *  off until the entry fills; EntryExpiryService owns the unfilled lifecycle. */
        boolean entryFilled,
        /** The short-window ATR, shown to the LLM alongside {@link #atr()} so it can see that a
         *  post-report window is wider than the 22-day one. Nullable. */
        BigDecimal atrShort,
        /** The price the protective leg actually rests at — the catastrophe backstop, buffered
         *  away from {@link #activeStop()}. Nullable for rows opened before V48. */
        BigDecimal brokerStop) {
}
