package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;

/** Deterministic SP2 market-derived PEAD signals. Nullable field = unavailable. */
public record MarketSignals(
        BigDecimal announcementCar1d,
        BigDecimal announcementCar3d,
        boolean carAvailable,
        BigDecimal abnormalVolume,
        BigDecimal momentum6_12m,
        BigDecimal adv
) {
    public static MarketSignals empty() {
        return new MarketSignals(null, null, false, null, null, null);
    }
}
