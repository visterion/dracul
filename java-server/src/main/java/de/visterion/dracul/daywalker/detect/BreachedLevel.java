package de.visterion.dracul.daywalker.detect;

import java.math.BigDecimal;

/** Deterministic pre-set-level breach for a held position, from the current close.
 *  STOP wins over TARGET (a stop breach is the more urgent event). */
public final class BreachedLevel {

    public static final String STOP = "STOP";
    public static final String TARGET = "TARGET";

    private BreachedLevel() {}

    /** "STOP" if close ≤ activeStop, else "TARGET" if close ≥ nextTarget, else null. */
    public static String evaluate(BigDecimal close, BigDecimal activeStop, BigDecimal nextTarget) {
        if (close == null) return null;
        if (activeStop != null && close.compareTo(activeStop) <= 0) return STOP;
        if (nextTarget != null && close.compareTo(nextTarget) >= 0) return TARGET;
        return null;
    }
}
