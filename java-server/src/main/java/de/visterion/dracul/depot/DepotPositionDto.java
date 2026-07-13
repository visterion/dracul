package de.visterion.dracul.depot;

import java.math.BigDecimal;

/**
 * A single position enriched with the live quote (when available) and derived per-position
 * metrics. {@code price}/{@code dayChangePercent} are null when quote enrichment failed; the
 * unrealized-PL fields come from Agora's positions snapshot and are unaffected by that failure.
 *
 * <p>{@code name}/{@code assetType}/{@code valueDate} are Saxo-native fields passed straight
 * through from {@link DepotPosition} — strings, never FX-converted. {@code nativePrice}/
 * {@code nativeCurrency} carry the pre-conversion quote price and the position's native currency
 * (both {@code null} when the position currency equals the account currency, i.e. no conversion
 * happened) so the GUI can render "167,89 € (191,13 $)".
 */
public record DepotPositionDto(String symbol, BigDecimal qty, BigDecimal avgEntryPrice,
        BigDecimal marketValue, BigDecimal unrealizedPl, BigDecimal unrealizedPlPct,
        BigDecimal price, BigDecimal dayChangePercent, BigDecimal weightPct, String currency,
        String name, String assetType, String valueDate,
        BigDecimal nativePrice, String nativeCurrency) {
}
