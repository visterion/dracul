package de.visterion.dracul.depot;

import java.math.BigDecimal;

/** A single open position, as returned by {@code get_positions}. */
public record DepotPosition(String symbol, BigDecimal qty, BigDecimal avgEntryPrice,
                             BigDecimal marketValue, BigDecimal unrealizedPl, String currency) {
}
