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

    /** Spec §6 test 8: N=4 events in one week → no interval. */
    @Test
    void fourEventsInOneWeekHaveNoInterval() {
        SignalStats s = BenchmarkStats.signal(List.of(obs(W1, 0.01), obs(W1.plusDays(1), 0.02),
                obs(W1.plusDays(2), 0.03), obs(W1.plusDays(3), 0.04)));
        assertThat(s.n()).isEqualTo(4);
        assertThat(s.weeks()).isEqualTo(1);
        assertThat(s.mean()).isEqualByComparingTo("0.025");
        assertThat(s.ciLow()).isNull();
        assertThat(s.ciHigh()).isNull();
    }

    /** Spec §6 test 8: 5 weeks → interval. Weekly means .01..05, t(4)=2.776. */
    @Test
    void fiveWeeksGiveAWeekClusteredTInterval() {
        SignalStats s = BenchmarkStats.signal(weekly(0.01, 0.02, 0.03, 0.04, 0.05));
        assertThat(s.weeks()).isEqualTo(5);
        assertThat(s.clusteredMean().doubleValue()).isCloseTo(0.03, within(1e-6));
        assertThat(s.ciLow().doubleValue()).isCloseTo(0.010371, within(1e-6));
        assertThat(s.ciHigh().doubleValue()).isCloseTo(0.049629, within(1e-6));
    }

    @Test
    void eventsOfOneWeekAreAveragedBeforeTheInterval() {
        List<BenchmarkStats.Obs> o = new ArrayList<>(List.of(obs(W1, 0.10), obs(W1.plusDays(1), -0.10)));
        for (int k = 1; k <= 4; k++) o.add(obs(W1.plusWeeks(k), 0.0));
        SignalStats s = BenchmarkStats.signal(o);
        assertThat(s.n()).isEqualTo(6);
        assertThat(s.weeks()).isEqualTo(5);
        assertThat(s.clusteredMean()).isEqualByComparingTo("0");
        assertThat(s.ciLow()).isEqualByComparingTo("0");
        assertThat(s.ciHigh()).isEqualByComparingTo("0");
    }

    @Test
    void medianAndHitRateAreEventLevel() {
        SignalStats s = BenchmarkStats.signal(weekly(-0.02, 0.01, 0.03, 0.05));
        assertThat(s.median()).isEqualByComparingTo("0.02");
        assertThat(s.hitRate()).isEqualByComparingTo("0.75");
    }

    @Test
    void noEventsIsTheEmptyStats() {
        assertThat(BenchmarkStats.signal(List.of())).isEqualTo(SignalStats.empty());
    }

    /** Welch over weekly means: var 5e-5 each, se 0.0044721, df 8 → t 2.306. */
    @Test
    void pairedUsesWelchOverWeeklyMeans() {
        PairedStats p = BenchmarkStats.paired(weekly(0.04, 0.05, 0.06, 0.05, 0.05), weekly(0.0, 0.01, -0.01, 0.0, 0.0));
        assertThat(p.pickedN()).isEqualTo(5);
        assertThat(p.rejectedN()).isEqualTo(5);
        assertThat(p.difference().doubleValue()).isCloseTo(0.05, within(1e-6));
        assertThat(p.ciLow().doubleValue()).isCloseTo(0.039687, within(1e-6));
        assertThat(p.ciHigh().doubleValue()).isCloseTo(0.060313, within(1e-6));
    }

    @Test
    void pairedIsNullWhenAGroupIsEmptyAndHasNoIntervalBelowFiveWeeks() {
        assertThat(BenchmarkStats.paired(weekly(0.01), List.of())).isNull();
        assertThat(BenchmarkStats.paired(List.of(), weekly(0.01))).isNull();
        PairedStats few = BenchmarkStats.paired(weekly(0.01, 0.02), weekly(0.0, 0.01, 0.0, 0.01, 0.0));
        assertThat(few.difference()).isNotNull();
        assertThat(few.ciLow()).isNull();
    }

    @Test
    void statusFollowsTheSpecThresholds() {
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(clustered(6, 0.01, 0.02, 0.03, 0.04, 0.05))))
                .isEqualTo(BenchmarkStatus.GREEN);
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(clustered(6, -0.01, -0.02, -0.03, -0.04, -0.05))))
                .isEqualTo(BenchmarkStatus.RED);
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(clustered(6, -0.02, -0.01, 0.0, 0.01, 0.02))))
                .isEqualTo(BenchmarkStatus.GREY);
        List<BenchmarkStats.Obs> twentyNine = new ArrayList<>(clustered(6, 0.01, 0.02, 0.03, 0.04, 0.05));
        twentyNine.removeLast();
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(twentyNine))).isEqualTo(BenchmarkStatus.INSUFFICIENT);
        assertThat(BenchmarkStats.status(BenchmarkStats.signal(clustered(8, 0.01, 0.02, 0.03, 0.04))))
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
