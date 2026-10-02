package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;

/** Deterministic SP2 market-derived PEAD signals. Nullable field = unavailable.
 *  {@code announcementCar2d} sums d0 and d0+1; {@code preReportClose} is the close of d0-1. */
public record MarketSignals(
        BigDecimal announcementCar1d,
        BigDecimal announcementCar3d,
        boolean carAvailable,
        BigDecimal abnormalVolume,
        BigDecimal momentum6_12m,
        BigDecimal adv,
        /** Abnormal return summed over the report-day bar d0 and the next bar d0+1 — the standard
         *  [0,+1] event window. Timing-agnostic: for an after-close report d0 is the pre-release
         *  day and the reaction lands in d0+1. Null when d0 <= 0, d0+1 is missing, or either
         *  day's abnormal return is unresolvable. */
        BigDecimal announcementCar2d,
        /** Close of bar d0-1, the last close before the report-day bar. Null when d0 <= 0 (no bar
         *  before it, or no bar on/after the report date at all). */
        BigDecimal preReportClose
) {
    public static MarketSignals empty() {
        return new MarketSignals(null, null, false, null, null, null, null, null);
    }
}
