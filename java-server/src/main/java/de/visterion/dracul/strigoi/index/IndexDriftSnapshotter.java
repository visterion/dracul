package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * EFFECTIVE / POST-stage enrichment for a tracked index-reconstitution event: the run-up and
 * post-effective decay/reversal observation the anomaly literature (Petajisto et al.) warns about.
 * Persisted as {@code post_snapshot}. Pure (given its injected facade) and fail-soft per field.
 *
 * <p>Everything derives from ONE daily-OHLC fetch spanning the announcement date to today:
 * <ul>
 *   <li><b>runUpPct</b> — percent move from the announcement bar's close to the effective bar's
 *       close (the front-running window). Null when either anchor bar is absent from the series.</li>
 *   <li><b>postEffectivePct</b> — percent move from the effective bar's close to the latest close
 *       (the give-back / continuation window). Null when the effective bar or latest bar is absent.</li>
 *   <li><b>reversalObserved</b> — true when the run-up and the post-effective move have OPPOSITE
 *       signs and the post-effective move clears the {@value #NOISE_THRESHOLD_PCT}% noise floor (a
 *       real give-back, not a rounding wobble). Null when either leg is unavailable.</li>
 *   <li><b>daysSinceEffective</b> — whole days from the effective date to {@code today}.</li>
 * </ul>
 *
 * <p><b>Source-down signalling.</b> The single source is the strict price feed
 * ({@link AgoraMarketData#dailyOhlcHistory}); an availability outage
 * ({@link MarketDataException.Kind#UNAVAILABLE}) PROPAGATES so {@link IndexEventEnricher} can apply
 * its {@code markIfSourceDown} short-circuit. A symbol-specific miss (empty bars) degrades every
 * field to null without throwing.
 */
@Component
public class IndexDriftSnapshotter {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int SCALE = 4;
    /** Post-effective move must exceed this percent to count as a real reversal (not noise). */
    private static final double NOISE_THRESHOLD_PCT = 1.0;
    private static final int MAX_LOOKBACK_DAYS = 400;
    private static final int MIN_LOOKBACK_DAYS = 20;

    private final AgoraMarketData marketData;

    public IndexDriftSnapshotter(AgoraMarketData marketData) {
        this.marketData = marketData;
    }

    /** EFFECTIVE/POST-stage drift snapshot; percent fields are whole percents (5.0 == +5%). */
    public record IndexDriftSnapshot(
            Double runUpPct,
            Double postEffectivePct,
            Boolean reversalObserved,
            Integer daysSinceEffective,
            boolean available) {

        static IndexDriftSnapshot unavailable() {
            return new IndexDriftSnapshot(null, null, null, null, false);
        }
    }

    /**
     * @param symbol           the constituent ticker.
     * @param announcementDate the run-up start anchor; null degrades runUpPct to null.
     * @param effectiveDate    the run-up end / post-effective start anchor; null degrades most fields.
     * @param today            the reconciliation date (passed in for deterministic testing).
     */
    public IndexDriftSnapshot snapshot(String symbol, LocalDate announcementDate,
                                       LocalDate effectiveDate, LocalDate today) {
        // Fetch enough calendar days to include the announcement bar, plus a small pad.
        int span = announcementDate == null
                ? MIN_LOOKBACK_DAYS
                : (int) ChronoUnit.DAYS.between(announcementDate, today) + 5;
        int lookback = Math.min(MAX_LOOKBACK_DAYS, Math.max(MIN_LOOKBACK_DAYS, span));
        List<OhlcBar> bars = marketData.dailyOhlcHistory(symbol, lookback);   // UNAVAILABLE propagates

        BigDecimal announcementClose = closeOnOrAfter(bars, announcementDate);
        BigDecimal effectiveClose = closeOnOrAfter(bars, effectiveDate);
        BigDecimal latestClose = bars.isEmpty() ? null : bars.get(bars.size() - 1).close();

        Double runUpPct = pctChange(announcementClose, effectiveClose);
        Double postEffectivePct = pctChange(effectiveClose, latestClose);

        Boolean reversalObserved = null;
        if (runUpPct != null && postEffectivePct != null) {
            boolean oppositeSigns = Math.signum(runUpPct) != 0
                    && Math.signum(postEffectivePct) != 0
                    && Math.signum(runUpPct) != Math.signum(postEffectivePct);
            reversalObserved = oppositeSigns && Math.abs(postEffectivePct) > NOISE_THRESHOLD_PCT;
        }

        Integer daysSinceEffective = (effectiveDate == null || today == null)
                ? null
                : (int) ChronoUnit.DAYS.between(effectiveDate, today);

        boolean available = runUpPct != null || postEffectivePct != null;
        return new IndexDriftSnapshot(runUpPct, postEffectivePct, reversalObserved,
                daysSinceEffective, available);
    }

    /** Close of the first bar on/after the given date; null when the date is null or no bar qualifies. */
    private static BigDecimal closeOnOrAfter(List<OhlcBar> bars, LocalDate date) {
        if (date == null) return null;
        for (OhlcBar b : bars) {
            if (b.date() != null && !b.date().isBefore(date)) return b.close();
        }
        return null;
    }

    /** Percent change from {@code from} to {@code to}; null on missing/zero base. */
    private static Double pctChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) return null;
        return to.divide(from, MC).subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
