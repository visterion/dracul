package de.visterion.dracul.benchmark;

import java.math.BigDecimal;

/** SIGNAL statistics for one agent and horizon. mean/median/hitRate are event-level; the 95 %
 *  interval is centred on clusteredMean (mean of ISO-week means of entry_date, plan ruling R11)
 *  with a Newey–West standard error over the weekly means, lag = horizon/5 weeks (final review I2).
 *  ciLow/ciHigh are null below max(5, 2·lag) weeks (8 for 20 d, 24 for 60 d). Fractions, not percent. */
public record SignalStats(int n, int weeks, BigDecimal mean, BigDecimal median, BigDecimal hitRate,
                          BigDecimal clusteredMean, BigDecimal ciLow, BigDecimal ciHigh) {

    public static SignalStats empty() {
        return new SignalStats(0, 0, null, null, null, null, null, null);
    }
}
