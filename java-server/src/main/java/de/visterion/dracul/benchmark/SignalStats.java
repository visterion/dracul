package de.visterion.dracul.benchmark;

import java.math.BigDecimal;

/** SIGNAL statistics for one agent and horizon. mean/median/hitRate are event-level; the 95 %
 *  interval is a t-interval over ISO-week means of entry_date, centred on clusteredMean
 *  (plan ruling R11). ciLow/ciHigh are null below 5 weeks. Fractions, not percent. */
public record SignalStats(int n, int weeks, BigDecimal mean, BigDecimal median, BigDecimal hitRate,
                          BigDecimal clusteredMean, BigDecimal ciLow, BigDecimal ciHigh) {

    public static SignalStats empty() {
        return new SignalStats(0, 0, null, null, null, null, null, null);
    }
}
