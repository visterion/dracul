package de.visterion.dracul.gropar;

import java.math.BigDecimal;

/** R-framework metrics for one position. Null fields + *Available=false when
 *  history/entry/stop are insufficient. {@code derivedNow} signals the controller
 *  to persist a freshly-derived initial stop. */
public record RiskMetrics(
        BigDecimal initialStop, boolean initialStopAvailable, boolean derivedNow, boolean initialStopBreached,
        BigDecimal r, boolean rAvailable, BigDecimal gainInR,
        BigDecimal mfePeakGainPct, BigDecimal mfePeakGainR, boolean mfeAvailable,
        BigDecimal givebackPct, boolean givebackBreached
) {
    public static RiskMetrics empty() {
        return new RiskMetrics(null, false, false, false, null, false, null, null, null, false, null, false);
    }
}
