package de.visterion.dracul.stopguard;

import java.math.BigDecimal;

/** Pure, total classification of a position's live price against its active
 *  trailing stop. No I/O. BREACHED when price &le; stop; PROXIMITY when price is
 *  within {@code atrMultiple}*atr above the stop; NONE otherwise or on any null. */
public final class StopZoneEvaluator {

    private StopZoneEvaluator() {}

    public static StopZone evaluate(BigDecimal price, BigDecimal activeStop,
                                    BigDecimal atr, double atrMultiple) {
        if (price == null || activeStop == null || atr == null) return StopZone.NONE;
        if (price.compareTo(activeStop) <= 0) return StopZone.BREACHED;
        BigDecimal bandTop = activeStop.add(atr.multiply(BigDecimal.valueOf(atrMultiple)));
        if (price.compareTo(bandTop) <= 0) return StopZone.PROXIMITY;
        return StopZone.NONE;
    }
}
