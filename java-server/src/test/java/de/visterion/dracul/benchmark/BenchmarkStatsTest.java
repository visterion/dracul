package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BenchmarkStatsTest {

    /** Monday of ISO week 2026-W02. */
    private static final LocalDate W1 = LocalDate.of(2026, 1, 5);

    private static BenchmarkStats.Obs obs(LocalDate d, double x) {
        return new BenchmarkStats.Obs(d, x);
    }

    /** One event per week k (0-based) with excess values[k]. */
    private static List<BenchmarkStats.Obs> weekly(double... values) {
        List<BenchmarkStats.Obs> out = new ArrayList<>();
        for (int k = 0; k < values.length; k++) out.add(obs(W1.plusWeeks(k), values[k]));
        return out;
    }

    /** {@code perWeek} events in each week k with excess bases[k]. */
    private static List<BenchmarkStats.Obs> clustered(int perWeek, double... bases) {
        List<BenchmarkStats.Obs> out = new ArrayList<>();
        for (int k = 0; k < bases.length; k++) {
            for (int j = 0; j < perWeek; j++) out.add(obs(W1.plusWeeks(k).plusDays(j % 5), bases[k]));
        }
        return out;
    }

    /** {@code perWeek} events in each of {@code weeks} weeks, base values cycling through {@code pattern}. */
    private static List<BenchmarkStats.Obs> cycled(int weeks, int perWeek, double... pattern) {
        double[] bases = new double[weeks];
        for (int k = 0; k < weeks; k++) bases[k] = pattern[k % pattern.length];
        return clustered(perWeek, bases);
    }

    /** Horizon 0 → lag 0 and the 5-week floor: the independent-weeks case of the old t-interval. */
    private static final int INDEPENDENT = 0;

    @Test
    void lagAndMinimumWeeksFollowTheHorizon() {
        assertThat(BenchmarkStats.lagWeeks(20)).isEqualTo(4);
        assertThat(BenchmarkStats.lagWeeks(60)).isEqualTo(12);
        assertThat(BenchmarkStats.lagWeeks(0)).isZero();
        assertThat(BenchmarkStats.minWeeks(20)).isEqualTo(8);
        assertThat(BenchmarkStats.minWeeks(60)).isEqualTo(24);
        assertThat(BenchmarkStats.minWeeks(INDEPENDENT)).isEqualTo(5);
    }

    /** Final review I2 test (a): with lag 0 the HAC variance of the mean is exactly s²/n.
     *  x = .01..05: s² = 2.5e-4, n = 5 → 5e-5. */
    @Test
    void hacWithLagZeroEqualsTheNaiveVarianceOfTheMean() {
        assertThat(BenchmarkStats.hacVarianceOfMean(new double[]{0.01, 0.02, 0.03, 0.04, 0.05}, 0))
                .isCloseTo(5e-5, within(1e-15));
    }

    /**
     * Final review I2 test (b), by hand. Weekly means .01..08 (a trend — strongly autocorrelated),
     * horizon 20 → L = 4, n = 8 (= the 8-week minimum). Residuals e = (−7,−5,−3,−1,1,3,5,7)·0.005.
     * In units of 0.005² = 2.5e-5: γ0 = 168/8 = 21, γ1 = 105/8 = 13.125, γ2 = 46/8 = 5.75,
     * γ3 = −5/8 = −0.625, γ4 = −44/8 = −5.5; Bartlett weights .8 .6 .4 .2 →
     * LRV = 21 + 2·(10.5 + 3.45 − 0.25 − 1.1) = 46.2; Var(mean) = 46.2/7 = 6.6 units = 1.65e-4,
     * SE = 0.0128452 — larger than the naive s/√n = √(24/8 units) = 0.0086603.
     * t(7) = 2.365 → 0.045 ± 0.0303790.
     */
    @Test
    void hacWidensTheIntervalForAutocorrelatedWeeks() {
        double[] x = {0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08};
        double hac = BenchmarkStats.hacVarianceOfMean(x, 4);
        assertThat(hac).isCloseTo(1.65e-4, within(1e-12));
        assertThat(Math.sqrt(hac)).isGreaterThan(Math.sqrt(BenchmarkStats.hacVarianceOfMean(x, 0)));
        assertThat(BenchmarkStats.hacVarianceOfMean(x, 0)).isCloseTo(7.5e-5, within(1e-12));

        SignalStats s = BenchmarkStats.signal(weekly(x), 20);
        assertThat(s.weeks()).isEqualTo(8);
        assertThat(s.clusteredMean().doubleValue()).isCloseTo(0.045, within(1e-6));
        assertThat(s.ciLow().doubleValue()).isCloseTo(0.014621, within(1e-6));
        assertThat(s.ciHigh().doubleValue()).isCloseTo(0.075379, within(1e-6));
    }

    /** Final review I2 test (c): 60 d → L = 12 → an interval needs 2L = 24 weeks. */
    @Test
    void sixtyDayIntervalsNeedTwentyFourWeeks() {
        SignalStats w23 = BenchmarkStats.signal(cycled(23, 2, 0.03, 0.05, 0.02, 0.04), 60);
        assertThat(w23.weeks()).isEqualTo(23);
        assertThat(w23.ciLow()).isNull();
        assertThat(w23.ciHigh()).isNull();
        SignalStats w24 = BenchmarkStats.signal(cycled(24, 2, 0.03, 0.05, 0.02, 0.04), 60);
        assertThat(w24.weeks()).isEqualTo(24);
        assertThat(w24.ciLow()).isNotNull();
        assertThat(w24.ciHigh()).isNotNull();
        // 20 d with 7 weeks: below the 8-week minimum.
        assertThat(BenchmarkStats.signal(cycled(7, 1, 0.01, 0.02), 20).ciLow()).isNull();
        assertThat(BenchmarkStats.signal(cycled(8, 1, 0.01, 0.02), 20).ciLow()).isNotNull();
    }

    /** Spec §6 test 8: N=4 events in one week → no interval. */
    @Test
    void fourEventsInOneWeekHaveNoInterval() {
        SignalStats s = BenchmarkStats.signal(List.of(obs(W1, 0.01), obs(W1.plusDays(1), 0.02),
                obs(W1.plusDays(2), 0.03), obs(W1.plusDays(3), 0.04)), 20);
        assertThat(s.n()).isEqualTo(4);
        assertThat(s.weeks()).isEqualTo(1);
        assertThat(s.mean()).isEqualByComparingTo("0.025");
        assertThat(s.ciLow()).isNull();
        assertThat(s.ciHigh()).isNull();
    }

    /** Lag 0 (independent weeks), 5 weeks → the old t-interval. Weekly means .01..05, t(4)=2.776. */
    @Test
    void independentWeeksGiveThePlainTInterval() {
        SignalStats s = BenchmarkStats.signal(weekly(0.01, 0.02, 0.03, 0.04, 0.05), INDEPENDENT);
        assertThat(s.weeks()).isEqualTo(5);
        assertThat(s.clusteredMean().doubleValue()).isCloseTo(0.03, within(1e-6));
        assertThat(s.ciLow().doubleValue()).isCloseTo(0.010371, within(1e-6));
        assertThat(s.ciHigh().doubleValue()).isCloseTo(0.049629, within(1e-6));
    }

    @Test
    void eventsOfOneWeekAreAveragedBeforeTheInterval() {
        List<BenchmarkStats.Obs> o = new ArrayList<>(List.of(obs(W1, 0.10), obs(W1.plusDays(1), -0.10)));
        for (int k = 1; k <= 7; k++) o.add(obs(W1.plusWeeks(k), 0.0));
        SignalStats s = BenchmarkStats.signal(o, 20);
        assertThat(s.n()).isEqualTo(9);
        assertThat(s.weeks()).isEqualTo(8);
        assertThat(s.clusteredMean()).isEqualByComparingTo("0");
        assertThat(s.ciLow()).isEqualByComparingTo("0");
        assertThat(s.ciHigh()).isEqualByComparingTo("0");
    }

    @Test
    void medianAndHitRateAreEventLevel() {
        SignalStats s = BenchmarkStats.signal(weekly(-0.02, 0.01, 0.03, 0.05), 20);
        assertThat(s.median()).isEqualByComparingTo("0.02");
        assertThat(s.hitRate()).isEqualByComparingTo("0.75");
    }

    @Test
    void noEventsIsTheEmptyStats() {
        assertThat(BenchmarkStats.signal(List.of(), 60)).isEqualTo(SignalStats.empty());
    }

    /** Welch over weekly means, lag 0: var 5e-5 each, se 0.0044721, df 8 → t 2.306. */
    @Test
    void pairedUsesWelchOverWeeklyMeans() {
        PairedStats p = BenchmarkStats.paired(weekly(0.04, 0.05, 0.06, 0.05, 0.05),
                weekly(0.0, 0.01, -0.01, 0.0, 0.0), INDEPENDENT);
        assertThat(p.pickedN()).isEqualTo(5);
        assertThat(p.rejectedN()).isEqualTo(5);
        assertThat(p.difference().doubleValue()).isCloseTo(0.05, within(1e-6));
        assertThat(p.ciLow().doubleValue()).isCloseTo(0.039687, within(1e-6));
        assertThat(p.ciHigh().doubleValue()).isCloseTo(0.060313, within(1e-6));
    }

    /**
     * HAC per group + Welch–Satterthwaite on (weeks − 1). Picked = the trend series of
     * {@link #hacWidensTheIntervalForAutocorrelatedWeeks} (HAC var 1.65e-4), rejected = 8 weeks of 0
     * (var 0) → df = vp² / (vp²/7) = 7, t 2.365: difference 0.045 ± 0.0303790.
     */
    @Test
    void pairedUsesTheHacVariancePerGroup() {
        PairedStats p = BenchmarkStats.paired(weekly(0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08),
                weekly(0, 0, 0, 0, 0, 0, 0, 0), 20);
        assertThat(p.difference().doubleValue()).isCloseTo(0.045, within(1e-6));
        assertThat(p.ciLow().doubleValue()).isCloseTo(0.014621, within(1e-6));
        assertThat(p.ciHigh().doubleValue()).isCloseTo(0.075379, within(1e-6));
    }

    @Test
    void pairedIsNullWhenAGroupIsEmptyAndHasNoIntervalBelowTheMinimumPerGroup() {
        assertThat(BenchmarkStats.paired(weekly(0.01), List.of(), 20)).isNull();
        assertThat(BenchmarkStats.paired(List.of(), weekly(0.01), 20)).isNull();
        PairedStats few = BenchmarkStats.paired(weekly(0.01, 0.02), weekly(0.0, 0.01, 0.0, 0.01, 0.0), INDEPENDENT);
        assertThat(few.difference()).isNotNull();
        assertThat(few.ciLow()).isNull();
        // 20 d: 8 picked weeks but only 7 rejected weeks → no interval.
        PairedStats sevenRejected = BenchmarkStats.paired(cycled(8, 1, 0.02, 0.03), cycled(7, 1, 0.0, 0.01), 20);
        assertThat(sevenRejected.ciLow()).isNull();
        assertThat(BenchmarkStats.paired(cycled(8, 1, 0.02, 0.03), cycled(8, 1, 0.0, 0.01), 20).ciLow()).isNotNull();
    }

    /** Status on 60 d: ≥ 30 events AND ≥ 24 weeks, then the CI side. */
    @Test
    void statusFollowsTheSpecThresholds() {
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(cycled(24, 2, 0.03, 0.05, 0.02, 0.04), 60)))
                .isEqualTo(BenchmarkStatus.GREEN);
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(cycled(24, 2, -0.03, -0.05, -0.02, -0.04), 60)))
                .isEqualTo(BenchmarkStatus.RED);
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(cycled(24, 2, 0.03, -0.02, 0.01, -0.04, 0.02, 0.0), 60)))
                .isEqualTo(BenchmarkStatus.GREY);
        List<BenchmarkStats.Obs> twentyNine = new ArrayList<>(cycled(24, 1, 0.03, 0.05, 0.02, 0.04));
        for (int k = 0; k < 5; k++) twentyNine.add(obs(W1.plusWeeks(k).plusDays(1), 0.03));
        assertThat(twentyNine).hasSize(29);
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(twentyNine, 60))).isEqualTo(BenchmarkStatus.INSUFFICIENT);
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(cycled(23, 3, 0.03, 0.05, 0.02, 0.04), 60)))
                .isEqualTo(BenchmarkStatus.INSUFFICIENT);
        assertThat(BenchmarkStats.status(SignalStats.empty())).isEqualTo(BenchmarkStatus.INSUFFICIENT);
    }

    @Test
    void tTableIsConservativeBetweenRows() {
        assertThat(BenchmarkStats.tCritical975(4)).isEqualTo(2.776);
        assertThat(BenchmarkStats.tCritical975(30)).isEqualTo(2.042);
        assertThat(BenchmarkStats.tCritical975(35)).isEqualTo(2.042);
        assertThat(BenchmarkStats.tCritical975(45)).isEqualTo(2.021);
        assertThat(BenchmarkStats.tCritical975(100)).isEqualTo(2.000);
        assertThat(BenchmarkStats.tCritical975(500)).isEqualTo(1.980);
    }
}
