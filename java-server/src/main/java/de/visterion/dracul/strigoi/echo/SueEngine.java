package de.visterion.dracul.strigoi.echo;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Deterministic SUE: Foster (1977) seasonal-random-walk time-series SUE with date-based seasonal
 *  alignment (robust to gaps in the EDGAR EPS series), plus cross-sectional decile with a z-band
 *  fallback for thin batches. */
@Component
public class SueEngine {

    private static final int MIN_ERRORS = 4;
    private static final int THIN_BATCH = 20;
    private static final long SEASON_DAYS = 365;
    private static final long PAIR_TOLERANCE_DAYS = 45;     // year-ago match within the history
    private static final long CURRENT_TOLERANCE_DAYS = 55;  // year-ago match for the current quarter
    private static final long REPORT_TO_PERIOD_END_LAG = 30; // announce date ~30d after period end

    /** history newest-first (EDGAR), generally NOT including the just-announced quarter. */
    public Sue timeSeriesSue(BigDecimal currentActual, LocalDate currentReportDate,
                             List<QuarterlyEps> historyNewestFirst) {
        if (currentActual == null || currentReportDate == null || historyNewestFirst == null
                || historyNewestFirst.size() < MIN_ERRORS + 1) {
            return Sue.unavailable();
        }
        // year-ago value for the just-announced quarter: target ≈ current period-end − 1 year
        LocalDate currentYearAgoTarget = currentReportDate
                .minusDays(REPORT_TO_PERIOD_END_LAG).minusDays(SEASON_DAYS);
        QuarterlyEps yearAgo = closest(historyNewestFirst, currentYearAgoTarget, CURRENT_TOLERANCE_DAYS);
        if (yearAgo == null) return Sue.unavailable();

        // seasonal forecast errors over history (date-paired, each entry vs its ~365d-earlier partner)
        List<Double> errors = new ArrayList<>();
        for (QuarterlyEps a : historyNewestFirst) {
            QuarterlyEps b = closest(historyNewestFirst,
                    a.periodEnd().minusDays(SEASON_DAYS), PAIR_TOLERANCE_DAYS);
            if (b != null && !b.periodEnd().equals(a.periodEnd())) {
                errors.add(a.eps().doubleValue() - b.eps().doubleValue());
            }
        }
        if (errors.size() < MIN_ERRORS) return Sue.unavailable();
        double std = sampleStd(errors);
        if (std <= 0.0) return Sue.unavailable();

        double sue = (currentActual.doubleValue() - yearAgo.eps().doubleValue()) / std;
        return new Sue(sue, null, false, true); // decile filled later by the enrichment batch
    }

    /** Trailing count (newest-first) of quarters whose EPS exceeds its ~365d-earlier seasonal
     *  predecessor; stops at the first non-beat or when no predecessor exists. */
    public int seasonalBeatStreak(List<QuarterlyEps> historyNewestFirst) {
        if (historyNewestFirst == null) return 0;
        int count = 0;
        for (QuarterlyEps a : historyNewestFirst) {
            QuarterlyEps b = closest(historyNewestFirst,
                    a.periodEnd().minusDays(SEASON_DAYS), PAIR_TOLERANCE_DAYS);
            if (b == null || b.periodEnd().equals(a.periodEnd())) break;
            if (a.eps().compareTo(b.eps()) > 0) count++; else break;
        }
        return count;
    }

    /** Cross-sectional decile (1..10) of one value within the batch; z-band fallback when thin. */
    public int decile(double value, List<Double> batch, boolean approximate) {
        if (approximate || batch.size() < THIN_BATCH) return zBand(value);
        long below = batch.stream().filter(v -> v < value).count();
        int d = (int) Math.ceil((below + 1) * 10.0 / batch.size());
        return Math.max(1, Math.min(10, d));
    }

    private static QuarterlyEps closest(List<QuarterlyEps> entries, LocalDate target, long toleranceDays) {
        QuarterlyEps best = null;
        long bestDiff = Long.MAX_VALUE;
        for (QuarterlyEps e : entries) {
            long diff = Math.abs(ChronoUnit.DAYS.between(e.periodEnd(), target));
            if (diff <= toleranceDays && diff < bestDiff) { bestDiff = diff; best = e; }
        }
        return best;
    }

    private static int zBand(double z) {
        if (z >= 2.0) return 10;
        if (z >= 1.5) return 9;
        if (z >= 1.0) return 8;
        if (z >= 0.5) return 7;
        if (z >= 0.0) return 6;
        if (z >= -0.5) return 5;
        if (z >= -1.0) return 4;
        if (z >= -1.5) return 3;
        if (z >= -2.0) return 2;
        return 1;
    }

    private static double sampleStd(List<Double> xs) {
        int n = xs.size();
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss = xs.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum();
        return Math.sqrt(ss / (n - 1));
    }
}
