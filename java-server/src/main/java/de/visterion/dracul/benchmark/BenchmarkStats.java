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
 * every interval is computed over WEEKLY means: average the events per week first, then a
 * t-interval over the weeks (Welch over weekly means for a difference). Fewer than 5 weeks → no
 * interval. The t-table is hard-coded (no commons-math dependency); between rows the smaller df
 * (larger t) is used — conservative.
 */
public final class BenchmarkStats {

    public static final int MIN_EVENTS = 30;
    public static final int MIN_WEEKS = 5;

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

    public static SignalStats signal(List<Obs> obs) {
        if (obs.isEmpty()) return SignalStats.empty();
        double[] x = obs.stream().mapToDouble(Obs::excess).sorted().toArray();
        double mean = Arrays.stream(x).average().orElseThrow();
        double median = x.length % 2 == 1 ? x[x.length / 2] : (x[x.length / 2 - 1] + x[x.length / 2]) / 2;
        double hit = Arrays.stream(x).filter(v -> v > 0).count() / (double) x.length;
        double[] weeks = weeklyMeans(obs);
        double wMean = mean(weeks);
        BigDecimal lo = null;
        BigDecimal hi = null;
        if (weeks.length >= MIN_WEEKS) {
            double half = tCritical975(weeks.length - 1) * Math.sqrt(variance(weeks, wMean) / weeks.length);
            lo = bd(wMean - half);
            hi = bd(wMean + half);
        }
        return new SignalStats(x.length, weeks.length, bd(mean), bd(median), bd(hit), bd(wMean), lo, hi);
    }

    public static PairedStats paired(List<Obs> picked, List<Obs> rejected) {
        if (picked.isEmpty() || rejected.isEmpty()) return null;
        double[] wp = weeklyMeans(picked);
        double[] wr = weeklyMeans(rejected);
        double mp = mean(wp);
        double mr = mean(wr);
        BigDecimal lo = null;
        BigDecimal hi = null;
        if (wp.length >= MIN_WEEKS && wr.length >= MIN_WEEKS) {
            double vp = variance(wp, mp) / wp.length;
            double vr = variance(wr, mr) / wr.length;
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
        if (s60 == null || s60.n() < MIN_EVENTS || s60.weeks() < MIN_WEEKS || s60.ciLow() == null) {
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

    /** Sample variance (n − 1); 0 for a single value. */
    private static double variance(double[] v, double mean) {
        if (v.length < 2) return 0;
        double ss = 0;
        for (double x : v) ss += (x - mean) * (x - mean);
        return ss / (v.length - 1);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
