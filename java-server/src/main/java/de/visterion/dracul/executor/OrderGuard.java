package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Final code-enforced guard on a broker order the LLM proposed. Guarantees a valid
 * protective stop, positive quantity, and that we only ever write to the configured
 * paper connection. Pure and deterministic.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class OrderGuard {

    public record Result(boolean ok, RejectReason reason) {
        static Result pass() { return new Result(true, null); }
        static Result fail(RejectReason r) { return new Result(false, r); }
    }

    /**
     * @param side "BUY" or "SELL"
     * @param qty order quantity; must be strictly positive
     * @param referencePrice the reference (entry) price the stop is measured against; must be positive
     * @param stopPrice the protective stop price; must be positive and on the correct side of the reference
     * @param stopMin the sizer-computed lower bound of the acceptable stop window; null skips the check
     * @param stopMax the sizer-computed upper bound of the acceptable stop window; null skips the check
     * @param connectionEnv the connection the order would hit
     * @param allowedConnection the only connection writes are permitted on (paper)
     */
    public Result check(String side, BigDecimal qty, BigDecimal referencePrice,
                        BigDecimal stopPrice, BigDecimal stopMin, BigDecimal stopMax,
                        String connectionEnv, String allowedConnection) {
        if (connectionEnv == null || !connectionEnv.equals(allowedConnection)) {
            return Result.fail(RejectReason.NON_SIM_CONNECTION);
        }
        if (qty == null || qty.signum() <= 0) {
            return Result.fail(RejectReason.SCHEMA_INVALID);
        }
        if (stopPrice == null || stopPrice.signum() <= 0
                || referencePrice == null || referencePrice.signum() <= 0) {
            return Result.fail(RejectReason.NO_STOP);
        }
        // Long: stop strictly below reference. Short: stop strictly above reference.
        // A malformed side (null or anything else) is an explicit rejection, never a guess.
        boolean stopValid;
        if ("BUY".equalsIgnoreCase(side)) {
            stopValid = stopPrice.compareTo(referencePrice) < 0;
        } else if ("SELL".equalsIgnoreCase(side)) {
            stopValid = stopPrice.compareTo(referencePrice) > 0;
        } else {
            return Result.fail(RejectReason.SCHEMA_INVALID);
        }
        if (!stopValid) return Result.fail(RejectReason.NO_STOP);
        if (stopMin != null && stopMax != null
                && (stopPrice.compareTo(stopMin) < 0 || stopPrice.compareTo(stopMax) > 0)) {
            return Result.fail(RejectReason.NO_STOP);
        }
        return Result.pass();
    }
}
