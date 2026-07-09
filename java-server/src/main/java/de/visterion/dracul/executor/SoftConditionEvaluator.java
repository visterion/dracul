package de.visterion.dracul.executor;

import java.math.BigDecimal;

/**
 * Pure soft-exit condition evaluator: chandelier-level breach and fast/slow moving-average
 * cross, plus the running confirmation count used to require N consecutive soft breaches
 * before acting (see {@code soft_confirm_min} in the rule version).
 */
public class SoftConditionEvaluator {

    public record SoftState(boolean chandelierBreach, boolean maBreak, int confirmCount) {
    }

    public SoftState evaluate(BigDecimal close, BigDecimal chandelierLevel, BigDecimal maFast,
            BigDecimal maSlow, int prevCount) {
        boolean chandelierBreach = close != null && chandelierLevel != null
                && close.compareTo(chandelierLevel) < 0;
        boolean maBreak = maFast != null && maSlow != null && maFast.compareTo(maSlow) < 0;
        boolean soft = chandelierBreach || maBreak;
        int confirmCount = soft ? prevCount + 1 : 0;
        return new SoftState(chandelierBreach, maBreak, confirmCount);
    }
}
