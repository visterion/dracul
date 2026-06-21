package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskMetricsServiceTest {

    private final RiskMetricsService svc = new RiskMetricsService(new RiskMetricsService.Params(
            new BigDecimal("3.0"), new BigDecimal("1.5"), new BigDecimal("0.35"), new BigDecimal("2.0")));

    private OhlcBar bar(int dayOffset, double low, double high, double close) {
        var c = BigDecimal.valueOf(close);
        return new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(dayOffset),
                c, BigDecimal.valueOf(high), BigDecimal.valueOf(low), c, 1000);
    }

    private List<OhlcBar> flat(int n, double px) {
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < n; i++) bars.add(bar(i, px, px, px));
        return bars;
    }

    @Test
    void derivesAndFreezesInitialStopFromAtr() {
        var m = svc.compute(flat(5, 100), new BigDecimal("100"), null, null, new BigDecimal("4"), true);
        assertThat(m.initialStop()).isEqualByComparingTo("88");   // 100 - 3*4
        assertThat(m.derivedNow()).isTrue();
        assertThat(m.r()).isEqualByComparingTo("12");
        assertThat(m.gainInR()).isEqualByComparingTo("0");
    }

    @Test
    void storedStopWinsAndIsNotRederived() {
        var m = svc.compute(flat(5, 112), new BigDecimal("100"), null, new BigDecimal("90"), new BigDecimal("4"), true);
        assertThat(m.initialStop()).isEqualByComparingTo("90");
        assertThat(m.derivedNow()).isFalse();
        assertThat(m.r()).isEqualByComparingTo("10");
        assertThat(m.gainInR()).isEqualByComparingTo("1.2");
        assertThat(m.initialStopBreached()).isFalse();
    }

    @Test
    void initialStopBreachedWhenCloseBelowStop() {
        var m = svc.compute(flat(5, 80), new BigDecimal("100"), null, new BigDecimal("90"), new BigDecimal("4"), true);
        assertThat(m.initialStopBreached()).isTrue();
    }

    @Test
    void mfeMeasuredSinceEntryDate() {
        var bars = List.of(bar(0, 95, 105, 100), bar(1, 110, 130, 125), bar(2, 105, 115, 110));
        var m = svc.compute(bars, new BigDecimal("100"), LocalDate.of(2025, 1, 2),
                new BigDecimal("90"), new BigDecimal("4"), true);
        assertThat(m.mfePeakGainPct()).isEqualByComparingTo("30");
        assertThat(m.mfePeakGainR()).isEqualByComparingTo("3");
    }

    @Test
    void givebackBreachedAfterActivationWhenPeakGivenBack() {
        var bars = List.of(bar(0, 110, 130, 125), bar(1, 105, 120, 110));
        var m = svc.compute(bars, new BigDecimal("100"), LocalDate.of(2025, 1, 1),
                new BigDecimal("90"), new BigDecimal("4"), true);
        assertThat(m.givebackBreached()).isTrue();
        assertThat(m.givebackPct()).isGreaterThan(new BigDecimal("0.35"));
    }

    @Test
    void givebackNotBreachedBelowActivation() {
        var bars = List.of(bar(0, 100, 110, 108), bar(1, 100, 105, 101));
        var m = svc.compute(bars, new BigDecimal("100"), LocalDate.of(2025, 1, 1),
                new BigDecimal("90"), new BigDecimal("4"), true);
        assertThat(m.givebackBreached()).isFalse();
    }

    @Test
    void emptyOnNoBarsOrNoEntry() {
        assertThat(svc.compute(List.of(), new BigDecimal("100"), null, null, null, false).initialStopAvailable()).isFalse();
        assertThat(svc.compute(flat(3, 100), null, null, null, new BigDecimal("4"), true).rAvailable()).isFalse();
    }

    @Test
    void noStopWhenAtrUnavailableAndNoStoredStop() {
        var m = svc.compute(flat(3, 100), new BigDecimal("100"), null, null, null, false);
        assertThat(m.initialStopAvailable()).isFalse();
        assertThat(m.rAvailable()).isFalse();
        assertThat(m.derivedNow()).isFalse();
    }
}
