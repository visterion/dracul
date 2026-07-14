package de.visterion.dracul.executor;

import java.math.BigDecimal;

/**
 * Thresholds and limits used by {@link VetoService#evaluate}. All monetary amounts are in
 * account currency unless noted otherwise.
 */
public record VetoConfig(
        double minConfidence,
        int maxPositions,
        BigDecimal totalBudget,
        double heatPct,
        int maxPerSector,
        BigDecimal minPrice,       // instrument ccy (v1: instrument ccy IS USD)
        int advMultiple,
        int maxSignalAgeDays,
        double chaseAtrMult,
        int pacePerWeek,
        int trancheCount,
        double driftAnchorAtrMult,   // BELOW_ANCHOR: drift-set band (default 0.0)
        double valueAnchorAtrMult,   // BELOW_ANCHOR: value/dip band (default 3.0)
        String instrumentCurrency) { // CURRENCY_MISMATCH: the single account/instrument currency the executor trades (config dracul.executor.instrument-currency, default USD)
}
