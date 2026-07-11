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
        double suggestedFraction) {
}
