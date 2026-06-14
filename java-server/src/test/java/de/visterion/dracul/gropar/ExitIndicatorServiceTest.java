package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExitIndicatorServiceTest {

    private final ExitIndicatorService svc = new ExitIndicatorService(
            new ExitIndicatorService.Params(22, new BigDecimal("3.0"), 50, 200, 1));

    /** N bars, close == i+1 (rising), high=close+1, low=close-1. */
    private List<OhlcBar> rising(int n) {
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < n; i++) {
            var c = new BigDecimal(i + 1);
            bars.add(new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000));
        }
        return bars;
    }

    private OhlcBar flat(int i, int px) {
        var c = new BigDecimal(px);
        return new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i), c, c, c, c, 1000);
    }

    @Test
    void computesGainLossVsEntry() {
        var ind = svc.compute(rising(30), new BigDecimal("10"), null, null);
        assertThat(ind.gainLossPct()).isEqualByComparingTo("200"); // close 30 vs entry 10 = +200%
    }

    @Test
    void ma200UnavailableWithShortHistory() {
        var ind = svc.compute(rising(60), new BigDecimal("10"), null, null);
        assertThat(ind.ma200Available()).isFalse();
        assertThat(ind.ma50Available()).isTrue();
    }

    @Test
    void chandelierBreachWhenCloseBelowStop() {
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < 24; i++) bars.add(flat(i, 100));
        bars.add(new OhlcBar(LocalDate.of(2025, 2, 1), new BigDecimal("100"),
                new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("50"), 1000));
        var ind = svc.compute(bars, new BigDecimal("90"), null, null);
        assertThat(ind.chandelierBreached()).isTrue();
        assertThat(ind.firedRules()).contains("CHANDELIER_STOP");
    }

    @Test
    void horizonElapsedFromVerdictDate() {
        var bars = rising(30);
        String createdAt = bars.get(bars.size() - 1).date().minusMonths(8).toString();
        var ind = svc.compute(bars, new BigDecimal("10"), createdAt, "6m");
        assertThat(ind.horizonElapsed()).isTrue();
        assertThat(ind.firedRules()).contains("TIME_STOP");
    }

    @Test
    void deathCrossDetectedAndFired() {
        // 200 bars declining so MA50 < MA200.
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < 210; i++) {
            var c = new BigDecimal(300 - i); // strictly declining
            bars.add(new OhlcBar(LocalDate.of(2024, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000));
        }
        var ind = svc.compute(bars, new BigDecimal("500"), null, null);
        assertThat(ind.ma200Available()).isTrue();
        assertThat(ind.maCrossState()).isEqualTo("DEATH_CROSS");
        assertThat(ind.firedRules()).contains("DEATH_CROSS");
    }

    @Test
    void emptyHistoryDegradesGracefully() {
        var ind = svc.compute(List.of(), new BigDecimal("10"), null, null);
        assertThat(ind.atrAvailable()).isFalse();
        assertThat(ind.ma50Available()).isFalse();
        assertThat(ind.firedRules()).isEmpty();
    }
}
