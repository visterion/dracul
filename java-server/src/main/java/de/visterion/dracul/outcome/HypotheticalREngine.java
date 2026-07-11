package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.PositionSizer;
import de.visterion.dracul.marketdata.OhlcBar;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure, stateless "what would have happened" simulator: walks a hypothetical price path forward
 * from an entry signal and reports the outcome in R-multiples (risk units).
 *
 * <p>Shared by two consumers: counterfactual tracking of rejected entry signals (did we correctly
 * skip a loser, or would the trade have worked) and triple-barrier labelling used for hunter
 * confidence calibration (did the signal hit +1R before -1R within a horizon). No LLM calls, no
 * repository/network access — deterministic arithmetic over an already-fetched bar list.
 *
 * <p>Entry is assumed to be a limit-at-reference fill (the reference price is used verbatim as
 * the assumed entry). The stop is derived via {@link PositionSizer#deriveStopAnchor}, the same
 * anchor formula used for real position sizing, so hypothetical and real stops never diverge.
 */
@Component
public class HypotheticalREngine {

    private static final BigDecimal NEG_ONE_R = BigDecimal.ONE.negate();

    private final PositionSizer positionSizer;

    public HypotheticalREngine(PositionSizer positionSizer) {
        this.positionSizer = positionSizer;
    }

    /**
     * Walks {@code barsAfterSignal} (oldest-first, one entry per trading day following the
     * signal) and computes the hypothetical R outcome.
     *
     * @param side "BUY" or "SELL"
     * @param referencePrice signal reference price, used as the assumed entry; null -> skipped
     * @param atr average true range at signal time; null -> skipped
     * @param swingLow recent swing low, nullable; widens the stop when further from entry than
     *                 the ATR-only anchor
     * @param barsAfterSignal daily bars following the signal, oldest-first; list positions ARE
     *                        trading days (bar 0 = trading day 1, bar 19 = trading day 20, ...)
     * @param horizonTradingDays number of trading-day bars the triple-barrier label is decided
     *                           within
     * @return the hypothetical outcome, or {@link HypotheticalOutcome#skipped} when inputs are
     *         insufficient to derive a stop
     */
    public HypotheticalOutcome walk(String side, BigDecimal referencePrice, BigDecimal atr,
            BigDecimal swingLow, List<OhlcBar> barsAfterSignal, int horizonTradingDays) {

        if (referencePrice == null || atr == null) {
            return HypotheticalOutcome.skipped("missing reference_price/atr");
        }

        boolean buy = "BUY".equalsIgnoreCase(side);
        BigDecimal entry = referencePrice;
        BigDecimal stop = PositionSizer.deriveStopAnchor(side, referencePrice, atr, swingLow);
        BigDecimal rPerShare = entry.subtract(stop).abs();
        if (rPerShare.signum() <= 0) {
            // Garbage upstream data (e.g. atr=0 with no wider swing low): no risk unit exists,
            // R math would divide by zero. Skip instead of fabricating an outcome.
            return HypotheticalOutcome.skipped("non-positive rPerShare (atr " + atr + ")");
        }
        BigDecimal favorableTarget = buy ? entry.add(rPerShare) : entry.subtract(rPerShare);

        // Full-path walk (not bounded by horizon): first bar where the adverse extreme reaches
        // the stop, regardless of whether the favorable target was also touched that bar.
        Integer stoppedOutIndex = null;
        for (int i = 0; i < barsAfterSignal.size(); i++) {
            if (touchesStop(barsAfterSignal.get(i), buy, stop)) {
                stoppedOutIndex = i;
                break;
            }
        }
        boolean wouldHaveStoppedOut = stoppedOutIndex != null;

        BigDecimal rAfter20d = rAfterN(barsAfterSignal, 20, stoppedOutIndex, entry, rPerShare, buy);
        BigDecimal rAfter60d = rAfterN(barsAfterSignal, 60, stoppedOutIndex, entry, rPerShare, buy);

        Boolean label = tripleBarrierLabel(barsAfterSignal, horizonTradingDays, buy, stop, favorableTarget);

        return new HypotheticalOutcome(entry, stop, rPerShare, rAfter20d, rAfter60d,
                wouldHaveStoppedOut, label, null);
    }

    /** Stop-first tie-break: a bar whose adverse extreme reaches the stop counts as stopped out,
     *  even if its favorable extreme also reaches the +1R target in the same bar. */
    private boolean touchesStop(OhlcBar bar, boolean buy, BigDecimal stop) {
        return buy ? bar.low().compareTo(stop) <= 0 : bar.high().compareTo(stop) >= 0;
    }

    private boolean touchesFavorable(OhlcBar bar, boolean buy, BigDecimal favorableTarget) {
        return buy ? bar.high().compareTo(favorableTarget) >= 0 : bar.low().compareTo(favorableTarget) <= 0;
    }

    private BigDecimal rAfterN(List<OhlcBar> bars, int n, Integer stoppedOutIndex,
            BigDecimal entry, BigDecimal rPerShare, boolean buy) {
        if (bars.size() < n) {
            return null;
        }
        if (stoppedOutIndex != null && stoppedOutIndex <= n - 1) {
            return NEG_ONE_R;
        }
        BigDecimal close = bars.get(n - 1).close();
        BigDecimal delta = buy ? close.subtract(entry) : entry.subtract(close);
        return delta.divide(rPerShare, 4, RoundingMode.HALF_UP);
    }

    private Boolean tripleBarrierLabel(List<OhlcBar> bars, int horizonTradingDays, boolean buy,
            BigDecimal stop, BigDecimal favorableTarget) {
        int windowEnd = Math.min(horizonTradingDays, bars.size());
        for (int i = 0; i < windowEnd; i++) {
            OhlcBar bar = bars.get(i);
            if (touchesStop(bar, buy, stop)) {
                return false; // stop-first on ambiguous bars
            }
            if (touchesFavorable(bar, buy, favorableTarget)) {
                return true;
            }
        }
        if (bars.size() >= horizonTradingDays) {
            return false; // horizon elapsed, neither barrier touched
        }
        return null; // undecided: fewer bars than horizon, nothing hit yet
    }
}
