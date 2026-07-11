package de.visterion.dracul.outcome;

import java.math.BigDecimal;

/**
 * Result of walking a hypothetical price path forward from a signal: what would have happened
 * to a position entered at {@code assumedEntry} with a protective stop at {@code assumedStop},
 * expressed in R-multiples (risk units).
 *
 * <p>Used by (a) counterfactual tracking of rejected entry signals ("what if we had taken this
 * trade anyway") and (b) triple-barrier labelling for hunter-confidence calibration ("would this
 * signal have hit +1R before -1R").
 *
 * <p>When {@code skippedReason} is non-null, every other field is null/false and must not be
 * interpreted as a real outcome.
 */
public record HypotheticalOutcome(
        BigDecimal assumedEntry,
        BigDecimal assumedStop,
        BigDecimal rPerShare,
        BigDecimal rAfter20d,
        BigDecimal rAfter60d,
        boolean wouldHaveStoppedOut,
        Boolean tripleBarrierLabel,
        String skippedReason) {

    public static HypotheticalOutcome skipped(String reason) {
        return new HypotheticalOutcome(null, null, null, null, null, false, null, reason);
    }
}
