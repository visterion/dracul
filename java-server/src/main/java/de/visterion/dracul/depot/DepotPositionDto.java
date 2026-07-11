package de.visterion.dracul.depot;

import java.math.BigDecimal;

/**
 * A single position enriched with the live quote (when available) and derived per-position
 * metrics. {@code price}/{@code dayChangePercent} are null when quote enrichment failed; the
 * unrealized-PL fields come from Agora's positions snapshot and are unaffected by that failure.
 */
public record DepotPositionDto(String symbol, BigDecimal qty, BigDecimal avgEntryPrice,
        BigDecimal marketValue, BigDecimal unrealizedPl, BigDecimal unrealizedPlPct,
        BigDecimal price, BigDecimal dayChangePercent, BigDecimal weightPct, String currency) {
}
