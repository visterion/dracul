package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.OhlcBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes deterministic exit indicators from daily OHLC history.
 *
 * <p>ATR is computed as a simple SMA of True Range values (v1 simplification;
 * Wilder smoothing would use exponential weighting but requires more history to
 * stabilise and is harder to unit-test deterministically).</p>
 *
 * <p>This class is pure Java — no Spring, no database dependencies.</p>
 */
public class ExitIndicatorService {

    /** Configuration parameters for indicator computation. */
    public record Params(
            int atrPeriod,           // number of TR bars to average (e.g. 22)
            BigDecimal atrMultiple,  // Chandelier multiplier (e.g. 3.0)
            int maFast,              // fast MA period (e.g. 50)
            int maSlow,              // slow MA period (e.g. 200)
            int minBarsFor52w        // minimum bars for 52-week window flag (prod ~250)
    ) {
        public Params {
            if (atrPeriod <= 0 || maFast <= 0 || maSlow <= maFast)
                throw new IllegalArgumentException("invalid ExitIndicatorService.Params");
        }
    }

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int GAIN_SCALE = 4;

    private final Params params;

    public ExitIndicatorService(Params params) {
        this.params = params;
    }

    /**
     * Computes exit indicators for a position.
     *
     * @param bars            OHLC history, oldest first; may be empty
     * @param entryPrice      position entry price; null → gainLossPct = null
     * @param verdictCreatedAt ISO-8601 date string of when the verdict was created (first 10 chars used); null → horizonElapsed = false
     * @param horizon         holding-period string e.g. "6m", "1y", "12m"; null → horizonElapsed = false
     * @return computed indicators; never throws even on empty/short history
     */
    public ExitIndicators compute(
            List<OhlcBar> bars,
            BigDecimal entryPrice,
            String verdictCreatedAt,
            String horizon) {

        List<String> firedRules = new ArrayList<>();

        // --- Empty / degenerate case ---
        if (bars == null || bars.isEmpty()) {
            return new ExitIndicators(
                    null, null,
                    null, false,
                    null, false,
                    null, false,
                    null, false,
                    "NEUTRAL",
                    null, null, false,
                    null,
                    false,
                    List.of());
        }

        int n = bars.size();
        OhlcBar last = bars.get(n - 1);
        BigDecimal currentClose = last.close();

        // --- Gain / loss vs entry ---
        BigDecimal gainLossPct = null;
        if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) != 0) {
            gainLossPct = currentClose
                    .subtract(entryPrice)
                    .divide(entryPrice, MC)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(GAIN_SCALE, RoundingMode.HALF_UP);
        }

        // --- True Range values (index 1..n-1 relative to bars list) ---
        // TR_i = max(high_i - low_i, |high_i - prevClose|, |low_i - prevClose|)
        List<BigDecimal> trValues = new ArrayList<>(n - 1);
        for (int i = 1; i < n; i++) {
            OhlcBar bar = bars.get(i);
            BigDecimal prevClose = bars.get(i - 1).close();
            BigDecimal hl = bar.high().subtract(bar.low()).abs();
            BigDecimal hpc = bar.high().subtract(prevClose).abs();
            BigDecimal lpc = bar.low().subtract(prevClose).abs();
            BigDecimal tr = hl.max(hpc).max(lpc);
            trValues.add(tr);
        }

        // --- ATR: SMA of last atrPeriod TR values ---
        // trValues.size() == bars.size() - 1, so atrAvailable iff bars.size() > atrPeriod
        boolean atrAvailable = trValues.size() >= params.atrPeriod();
        BigDecimal atr = null;
        if (atrAvailable) {
            List<BigDecimal> lastTrs = trValues.subList(trValues.size() - params.atrPeriod(), trValues.size());
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal tr : lastTrs) sum = sum.add(tr);
            atr = sum.divide(BigDecimal.valueOf(params.atrPeriod()), MC);
        }

        // --- Chandelier stop = highestHigh(last atrPeriod bars) - atrMultiple * atr ---
        BigDecimal chandelierStop = null;
        boolean chandelierBreached = false;
        if (atrAvailable) {
            // last atrPeriod bars: from index (n - atrPeriod) to (n-1)
            int startIdx = n - params.atrPeriod();
            BigDecimal highestHigh = bars.get(startIdx).high();
            for (int i = startIdx + 1; i < n; i++) {
                if (bars.get(i).high().compareTo(highestHigh) > 0) {
                    highestHigh = bars.get(i).high();
                }
            }
            chandelierStop = highestHigh.subtract(params.atrMultiple().multiply(atr, MC));
            chandelierBreached = currentClose.compareTo(chandelierStop) < 0;
            if (chandelierBreached) {
                firedRules.add(ExitRules.CHANDELIER_STOP);
            }
        }

        // --- Moving averages ---
        BigDecimal ma50 = null;
        boolean ma50Available = n >= params.maFast();
        if (ma50Available) {
            ma50 = sma(bars, n - params.maFast(), n);
        }

        BigDecimal ma200 = null;
        boolean ma200Available = n >= params.maSlow();
        if (ma200Available) {
            ma200 = sma(bars, n - params.maSlow(), n);
        }

        // --- MA cross state ---
        String maCrossState = "NEUTRAL";
        if (ma50Available && ma200Available) {
            if (ma50.compareTo(ma200) < 0) {
                maCrossState = ExitRules.DEATH_CROSS;
                firedRules.add(ExitRules.DEATH_CROSS);
            } else {
                maCrossState = "BULLISH";
            }
        }

        // --- 52-week high/low (computed from all available bars; flag reflects threshold) ---
        BigDecimal high52w = bars.get(0).high();
        BigDecimal low52w = bars.get(0).low();
        for (int i = 1; i < n; i++) {
            if (bars.get(i).high().compareTo(high52w) > 0) high52w = bars.get(i).high();
            if (bars.get(i).low().compareTo(low52w) < 0) low52w = bars.get(i).low();
        }
        boolean window52wAvailable = n >= params.minBarsFor52w();

        // --- Horizon elapsed ---
        boolean horizonElapsed = computeHorizonElapsed(verdictCreatedAt, horizon, last.date());
        if (horizonElapsed) {
            firedRules.add(ExitRules.TIME_STOP);
        }

        return new ExitIndicators(
                currentClose, gainLossPct,
                atr, atrAvailable,
                chandelierStop, chandelierBreached,
                ma50, ma50Available,
                ma200, ma200Available,
                maCrossState,
                high52w, low52w, window52wAvailable,
                null,           // daysHeld — v1: null, controller computes if needed
                horizonElapsed,
                List.copyOf(firedRules));
    }

    // --- Helpers ---

    /** SMA of close prices from bars[fromIdx] (inclusive) to bars[toIdx] (exclusive). */
    private static BigDecimal sma(List<OhlcBar> bars, int fromIdx, int toIdx) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = fromIdx; i < toIdx; i++) sum = sum.add(bars.get(i).close());
        return sum.divide(BigDecimal.valueOf(toIdx - fromIdx), MC);
    }

    /**
     * Returns true iff the holding horizon has elapsed relative to the last bar date.
     *
     * @param verdictCreatedAt ISO-8601 date string (first 10 chars used), e.g. "2024-05-30T..."
     * @param horizon          "<n>m" for months or "<n>y" for years, e.g. "6m", "1y", "12m"
     * @param lastBarDate      date of the most recent bar
     */
    private static boolean computeHorizonElapsed(
            String verdictCreatedAt, String horizon, LocalDate lastBarDate) {
        if (verdictCreatedAt == null || horizon == null || lastBarDate == null) return false;
        try {
            String dateStr = verdictCreatedAt.length() >= 10
                    ? verdictCreatedAt.substring(0, 10)
                    : verdictCreatedAt;
            LocalDate createdDate = LocalDate.parse(dateStr);
            Period period = parseHorizon(horizon);
            if (period == null) return false;
            LocalDate targetDate = createdDate.plus(period);
            // elapsed if last bar date is on or after the target date
            return !lastBarDate.isBefore(targetDate);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses a horizon string into a {@link Period}.
     * Supports "<n>m" (months) and "<n>y" (years).
     *
     * @return parsed Period, or null if unparseable
     */
    private static Period parseHorizon(String horizon) {
        if (horizon == null || horizon.isBlank()) return null;
        String s = horizon.trim().toLowerCase();
        try {
            if (s.endsWith("m")) {
                int months = Integer.parseInt(s.substring(0, s.length() - 1));
                return Period.ofMonths(months);
            } else if (s.endsWith("y")) {
                int years = Integer.parseInt(s.substring(0, s.length() - 1));
                return Period.ofYears(years);
            }
        } catch (NumberFormatException ignored) {
            // fall through to null
        }
        return null;
    }
}
