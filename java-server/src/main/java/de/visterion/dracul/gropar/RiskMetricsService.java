package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.OhlcBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Computes R-framework risk metrics for a held position: the frozen ATR initial
 * stop, the risk unit R, gain in R, the max favourable excursion (MFE) since
 * entry, and the giveback (peak-drawdown) breach. Pure Java — no Spring, no DB.
 */
public class RiskMetricsService {

    /** Configuration for risk computation. */
    public record Params(
            BigDecimal initialStopAtrMultiple, // k in entry - k*ATR (e.g. 3.0)
            BigDecimal givebackActivationR,    // min peak gain in R before giveback can fire (e.g. 1.5)
            BigDecimal givebackThreshold,      // give-back fraction of peak that fires (e.g. 0.35)
            BigDecimal givebackAtrMultiple     // alt trigger: drawdown-from-peak in ATR (e.g. 2.0)
    ) {}

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Params params;

    public RiskMetricsService(Params params) {
        this.params = params;
    }

    public RiskMetrics compute(
            List<OhlcBar> bars,
            BigDecimal entryPrice,
            LocalDate entryDate,
            BigDecimal storedInitialStop,
            BigDecimal atr,
            boolean atrAvailable) {

        if (bars == null || bars.isEmpty() || entryPrice == null) {
            return RiskMetrics.empty();
        }

        BigDecimal currentClose = bars.get(bars.size() - 1).close();

        // --- Initial stop: stored (frozen) wins; else derive from ATR now ---
        BigDecimal initialStop = storedInitialStop;
        boolean derivedNow = false;
        if (initialStop == null && atrAvailable && atr != null) {
            initialStop = entryPrice.subtract(params.initialStopAtrMultiple().multiply(atr, MC));
            derivedNow = true;
        }
        boolean initialStopAvailable = initialStop != null;

        // --- R, gain in R, stop breach ---
        BigDecimal r = null;
        BigDecimal gainInR = null;
        boolean initialStopBreached = false;
        if (initialStopAvailable) {
            BigDecimal rawR = entryPrice.subtract(initialStop);
            if (rawR.compareTo(BigDecimal.ZERO) > 0) {
                r = rawR;
                gainInR = currentClose.subtract(entryPrice).divide(r, MC).setScale(SCALE, RoundingMode.HALF_UP);
            }
            initialStopBreached = currentClose.compareTo(initialStop) < 0;
        }
        boolean rAvailable = r != null;

        // --- MFE (peak high) since entry date; fall back to all bars if entryDate after all ---
        BigDecimal peakHigh = peakHighSince(bars, entryDate);
        if (peakHigh == null) peakHigh = peakHighSince(bars, null);
        boolean mfeAvailable = peakHigh != null;

        BigDecimal mfePeakGainPct = null;
        BigDecimal mfePeakGainR = null;
        if (mfeAvailable && entryPrice.compareTo(BigDecimal.ZERO) != 0) {
            mfePeakGainPct = peakHigh.subtract(entryPrice).divide(entryPrice, MC)
                    .multiply(HUNDRED).setScale(SCALE, RoundingMode.HALF_UP);
            if (rAvailable) {
                mfePeakGainR = peakHigh.subtract(entryPrice).divide(r, MC).setScale(SCALE, RoundingMode.HALF_UP);
            }
        }

        // --- Giveback (peak-drawdown) ---
        BigDecimal givebackPct = null; // fraction of peak gain given back, 0..1
        boolean givebackBreached = false;
        if (mfeAvailable && mfePeakGainPct != null && mfePeakGainPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal currentGainPct = currentClose.subtract(entryPrice).divide(entryPrice, MC).multiply(HUNDRED);
            givebackPct = mfePeakGainPct.subtract(currentGainPct).divide(mfePeakGainPct, MC)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            boolean activated = mfePeakGainR != null
                    && mfePeakGainR.compareTo(params.givebackActivationR()) >= 0;
            if (activated) {
                boolean byFraction = givebackPct.compareTo(params.givebackThreshold()) > 0;
                boolean byAtr = atrAvailable && atr != null
                        && peakHigh.subtract(currentClose)
                            .compareTo(params.givebackAtrMultiple().multiply(atr, MC)) > 0;
                givebackBreached = byFraction || byAtr;
            }
        }

        return new RiskMetrics(
                initialStop, initialStopAvailable, derivedNow, initialStopBreached,
                r, rAvailable, gainInR,
                mfePeakGainPct, mfePeakGainR, mfeAvailable,
                givebackPct, givebackBreached);
    }

    /** Highest high over bars on/after {@code from} (or all bars if from is null); null if none match. */
    private static BigDecimal peakHighSince(List<OhlcBar> bars, LocalDate from) {
        BigDecimal peak = null;
        for (OhlcBar b : bars) {
            if (from != null && b.date() != null && b.date().isBefore(from)) continue;
            if (peak == null || b.high().compareTo(peak) > 0) peak = b.high();
        }
        return peak;
    }
}
