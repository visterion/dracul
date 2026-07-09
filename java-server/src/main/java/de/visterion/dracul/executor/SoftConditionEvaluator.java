package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Pure soft-exit condition evaluator: chandelier-level breach and fast/slow moving-average
 * cross, plus the running confirmation count used to require N consecutive soft breaches
 * before acting (see {@code soft_confirm_min} in the rule version).
 *
 * <p>Breach direction depends on {@code side}: a BUY (long) breaches when price falls below
 * the chandelier level / the fast MA falls below the slow MA; a SELL (short) breaches on the
 * mirror-image move upward.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class SoftConditionEvaluator {

    public record SoftState(boolean chandelierBreach, boolean maBreak, int confirmCount) {
    }

    public SoftState evaluate(BigDecimal close, BigDecimal chandelierLevel, BigDecimal maFast,
            BigDecimal maSlow, String side, int prevCount) {
        boolean sell = "SELL".equalsIgnoreCase(side);
        boolean chandelierBreach = close != null && chandelierLevel != null
                && (sell ? close.compareTo(chandelierLevel) > 0 : close.compareTo(chandelierLevel) < 0);
        boolean maBreak = maFast != null && maSlow != null
                && (sell ? maFast.compareTo(maSlow) > 0 : maFast.compareTo(maSlow) < 0);
        boolean soft = chandelierBreach || maBreak;
        int confirmCount = soft ? prevCount + 1 : 0;
        return new SoftState(chandelierBreach, maBreak, confirmCount);
    }
}
