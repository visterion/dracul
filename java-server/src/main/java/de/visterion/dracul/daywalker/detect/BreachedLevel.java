package de.visterion.dracul.daywalker.detect;

import java.math.BigDecimal;

/** Deterministic pre-set-level breach for a held position, from the current close.
 *  STOP wins over TARGET (a stop breach is the more urgent event). */
public final class BreachedLevel {

    public static final String STOP = "STOP";
    public static final String TARGET = "TARGET";

    private BreachedLevel() {}

    /** Long-position evaluation (status quo): "STOP" if close ≤ activeStop, else "TARGET" if close ≥ nextTarget. */
    public static String evaluate(BigDecimal close, BigDecimal activeStop, BigDecimal nextTarget) {
        return evaluate(close, activeStop, nextTarget, false);
    }

    /** Direction-aware evaluation. For a short the stop sits ABOVE the price (breach when
     *  close >= activeStop) and the target below (close <= nextTarget). STOP wins over TARGET. */
    public static String evaluate(BigDecimal close, BigDecimal activeStop, BigDecimal nextTarget,
                                  boolean isShort) {
        if (close == null) return null;
        int stopCmp = isShort ? -1 : 1;
        if (activeStop != null && close.compareTo(activeStop) * stopCmp <= 0) return STOP;
        if (nextTarget != null && close.compareTo(nextTarget) * stopCmp >= 0) return TARGET;
        return null;
    }
}
