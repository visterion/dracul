package de.visterion.dracul.depot;

import java.math.BigDecimal;

/**
 * Depot-level rollups derived from enriched positions. All fields scale 2 / HALF_UP; a field is
 * null when its denominator would be zero (guarded div-by-zero) or when quote enrichment failed
 * (day-change fields only).
 */
public record DepotAggregates(BigDecimal investedValue, BigDecimal totalUnrealizedPl,
        BigDecimal totalUnrealizedPlPct, BigDecimal dayChangeAbs, BigDecimal dayChangePct) {
}
