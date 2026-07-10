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
        int pacePerWeek) {
}
