package de.visterion.dracul.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spec §4.1 statistics. Events in the same ISO week of entry share the market (review M2), so
 * every interval is computed over WEEKLY means: average the events per week first. A 20- or
 * 60-trading-day holding window spans 4 or 12 weeks, so consecutive weekly means overlap and are
 * autocorrelated (final review I2): the standard error of their mean is a Newey–West HAC estimate
 * (Bartlett kernel) with lag L = ceil(horizonDays / 5) weeks, scaled by n/(n−1) so that L = 0
 * reproduces the plain s/√n. Interval = clustered mean ± t(weeks − 1) · SE, only with at least
 * max(5, 2L) weeks (8 for 20 d, 24 for 60 d). Difference picked − rejected: HAC variance per
 * group, Welch–Satterthwaite df on (weeks − 1) per group, the same minimum per group. The
 * weekly-mean series is ordered by ISO week; weeks without an event are skipped, so the lag is
 * counted in observed weeks. The t-table is hard-coded (no commons-math dependency); between rows
 * the smaller df (larger t) is used — conservative.
 */
public final class BenchmarkStats {

    public static final int MIN_EVENTS = 30;
    /** Absolute floor for an interval; the horizon-dependent minimum is {@link #minWeeks}. */
    public static final int MIN_WEEKS = 5;
    /** The traffic light is driven by the 60-day SIGNAL statistics (spec §4.3). */
    public static final int STATUS_HORIZON = 60;

    public record Obs(LocalDate entryDate, double excess) {}

    private static final double[] T975 = {Double.NaN,
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

    private BenchmarkStats() {
    }

    static double tCritical975(int df) {
        if (df < 1) throw new IllegalArgumentException("df must be >= 1, was " + df);
        if (df <= 30) return T975[df];
        if (df < 40) return 2.042;
        if (df < 60) return 2.021;
        if (df < 120) return 2.000;
        return 1.980;
    }

    /** Newey–West lag in weeks for a holding window of {@code horizonDays} trading days. */
    public static int lagWeeks(int horizonDays) {
        return Math.max(0, (int) Math.ceil(horizonDays / 5.0));
    }

    /** Weeks needed for an interval: max(5, 2L) — 8 for 20 d, 24 for 60 d. */
    public static int minWeeks(int horizonDays) {
        return Math.max(MIN_WEEKS, 2 * lagWeeks(horizonDays));
    }

    /**
     * Newey–West (Bartlett kernel) variance of the MEAN of {@code x}: long-run variance
     * γ0 + 2·Σ_{j=1..L} (1 − j/(L+1))·γj with γj = Σ e_t·e_{t−j} / n, divided by (n − 1) — i.e.
     * LRV/n times the small-sample factor n/(n−1), so lag 0 equals s²/n. Lags ≥ n are ignored.
     */
    static double hacVarianceOfMean(double[] x, int lag) {
        int n = x.length;
        if (n < 2) return 0;
        double m = mean(x);
        double[] e = new double[n];
        for (int t = 0; t < n; t++) e[t] = x[t] - m;
        double lrv = autocovariance(e, 0);
        for (int j = 1; j <= Math.min(lag, n - 1); j++) {
            lrv += 2 * (1 - j / (lag + 1.0)) * autocovariance(e, j);
        }
        return Math.max(0, lrv) / (n - 1);
    }

    private static double autocovariance(double[] e, int j) {
        double sum = 0;
        for (int t = j; t < e.length; t++) sum += e[t] * e[t - j];
        return sum / e.length;
    }

    public static SignalStats signal(List<Obs> obs, int horizonDays) {
        if (obs.isEmpty()) return SignalStats.empty();
        double[] x = obs.stream().mapToDouble(Obs::excess).sorted().toArray();
        double mean = Arrays.stream(x).average().orElseThrow();
        double median = x.length % 2 == 1 ? x[x.length / 2] : (x[x.length / 2 - 1] + x[x.length / 2]) / 2;
        double hit = Arrays.stream(x).filter(v -> v > 0).count() / (double) x.length;
        double[] weeks = weeklyMeans(obs);
        double wMean = mean(weeks);
        BigDecimal lo = null;
        BigDecimal hi = null;
        if (weeks.length >= minWeeks(horizonDays)) {
            double half = tCritical975(weeks.length - 1) * Math.sqrt(hacVarianceOfMean(weeks, lagWeeks(horizonDays)));
            lo = bd(wMean - half);
            hi = bd(wMean + half);
        }
        return new SignalStats(x.length, weeks.length, bd(mean), bd(median), bd(hit), bd(wMean), lo, hi);
    }

    public static PairedStats paired(List<Obs> picked, List<Obs> rejected, int horizonDays) {
        if (picked.isEmpty() || rejected.isEmpty()) return null;
        double[] wp = weeklyMeans(picked);
        double[] wr = weeklyMeans(rejected);
        double mp = mean(wp);
        double mr = mean(wr);
        BigDecimal lo = null;
        BigDecimal hi = null;
        int need = minWeeks(horizonDays);
        if (wp.length >= need && wr.length >= need) {
            double vp = hacVarianceOfMean(wp, lagWeeks(horizonDays));
            double vr = hacVarianceOfMean(wr, lagWeeks(horizonDays));
            double se = Math.sqrt(vp + vr);
            double df = (vp + vr == 0) ? wp.length + wr.length - 2
                    : (vp + vr) * (vp + vr) / (vp * vp / (wp.length - 1) + vr * vr / (wr.length - 1));
            double half = tCritical975((int) Math.floor(df + 1e-9)) * se;
            lo = bd(mp - mr - half);
            hi = bd(mp - mr + half);
        }
        return new PairedStats(picked.size(), bd(meanOf(picked)), wp.length, rejected.size(), bd(meanOf(rejected)),
                wr.length, bd(mp - mr), lo, hi);
    }

    public static BenchmarkStatus status(SignalStats s60) {
        if (s60 == null || s60.n() < MIN_EVENTS || s60.weeks() < minWeeks(STATUS_HORIZON) || s60.ciLow() == null) {
            return BenchmarkStatus.INSUFFICIENT;
        }
        if (s60.ciLow().signum() > 0) return BenchmarkStatus.GREEN;
        if (s60.ciHigh().signum() < 0) return BenchmarkStatus.RED;
        return BenchmarkStatus.GREY;
    }

    static double[] weeklyMeans(List<Obs> obs) {
        Map<Integer, double[]> byWeek = new TreeMap<>();
        for (Obs o : obs) {
            int week = o.entryDate().get(IsoFields.WEEK_BASED_YEAR) * 100
                    + o.entryDate().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            double[] acc = byWeek.computeIfAbsent(week, k -> new double[2]);
            acc[0] += o.excess();
            acc[1]++;
        }
        return byWeek.values().stream().mapToDouble(a -> a[0] / a[1]).toArray();
    }

    private static double mean(double[] v) {
        return Arrays.stream(v).average().orElse(0);
    }

    private static double meanOf(List<Obs> obs) {
        return obs.stream().mapToDouble(Obs::excess).average().orElse(0);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
