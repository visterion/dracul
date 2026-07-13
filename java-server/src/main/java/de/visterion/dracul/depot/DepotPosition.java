package de.visterion.dracul.depot;

import java.math.BigDecimal;

/**
 * A single open position, as returned by {@code get_positions}. {@code description},
 * {@code assetType}, {@code valueDate} are Saxo-native fields (nullable — Alpaca positions carry
 * {@code null} description/valueDate); they are passed straight through to the GUI, never
 * FX-converted.
 */
public record DepotPosition(String symbol, String description, BigDecimal qty, BigDecimal avgEntryPrice,
                             BigDecimal marketValue, BigDecimal unrealizedPl, String currency,
                             String assetType, String valueDate) {
}
