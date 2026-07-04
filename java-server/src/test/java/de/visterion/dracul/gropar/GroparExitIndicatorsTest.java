package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class GroparExitIndicatorsTest {

    private OhlcBar bar(String d, String c) {
        BigDecimal close = new BigDecimal(c);
        return new OhlcBar(LocalDate.parse(d), close, close, close, close, 1000L);
    }

    private GroparExitIndicators withTa(ExitTa ta) {
        AgoraResearch research = Mockito.mock(AgoraResearch.class);
        when(research.exitTa(any(), anyInt(), any(), anyInt(), anyInt(), anyInt())).thenReturn(ta);
        return new GroparExitIndicators(research, 22, new BigDecimal("3.0"), 50, 200, 250);
    }

    private ExitTa ta(String atr, String chandelier, boolean breached, String crossState) {
        return new ExitTa(atr == null ? null : new BigDecimal(atr), atr != null,
                chandelier == null ? null : new BigDecimal(chandelier), breached,
                new BigDecimal("105"), true, new BigDecimal("100"), true, crossState,
                new BigDecimal("120"), new BigDecimal("90"), true);
    }

    @Test void gainLossVsEntryAndMapping() {
        // currentClose from bars = 30, entry 10 → +200%
        var bars = List.of(bar("2025-01-01", "20"), bar("2025-01-02", "30"));
        var ind = withTa(ta("2", "25", false, "BULLISH"))
                .compute("AAPL", bars, new BigDecimal("10"), null, null);
        assertThat(ind.currentClose()).isEqualByComparingTo("30");
        assertThat(ind.gainLossPct()).isEqualByComparingTo("200");
        assertThat(ind.atr()).isEqualByComparingTo("2");
        assertThat(ind.ma50()).isEqualByComparingTo("105");   // maFast -> ma50
        assertThat(ind.ma200()).isEqualByComparingTo("100");  // maSlow -> ma200
        assertThat(ind.maCrossState()).isEqualTo("BULLISH");
        assertThat(ind.firedRules()).isEmpty();
    }

    @Test void chandelierBreachFiresRule() {
        var bars = List.of(bar("2025-01-02", "30"));
        var ind = withTa(ta("2", "35", true, "BULLISH"))
                .compute("AAPL", bars, new BigDecimal("10"), null, null);
        assertThat(ind.chandelierBreached()).isTrue();
        assertThat(ind.firedRules()).contains(ExitRules.CHANDELIER_STOP);
    }

    @Test void deathCrossFiresRule() {
        var bars = List.of(bar("2025-01-02", "30"));
        var ind = withTa(ta("2", "25", false, "DEATH_CROSS"))
                .compute("AAPL", bars, new BigDecimal("10"), null, null);
        assertThat(ind.maCrossState()).isEqualTo("DEATH_CROSS");
        assertThat(ind.firedRules()).contains(ExitRules.DEATH_CROSS);
    }

    @Test void horizonElapsedFiresTimeStop() {
        var last = bar("2025-09-01", "30");
        var bars = List.of(last);
        String createdAt = LocalDate.parse("2025-01-01").toString(); // 8 months before, horizon 6m
        var ind = withTa(ta("2", "25", false, "BULLISH"))
                .compute("AAPL", bars, new BigDecimal("10"), createdAt, "6m");
        assertThat(ind.horizonElapsed()).isTrue();
        assertThat(ind.firedRules()).contains(ExitRules.TIME_STOP);
    }

    @Test void horizonNotElapsed() {
        var bars = List.of(bar("2025-03-01", "30"));
        String createdAt = LocalDate.parse("2025-01-01").toString(); // 2 months, horizon 1y
        var ind = withTa(ta("2", "25", false, "BULLISH"))
                .compute("AAPL", bars, new BigDecimal("10"), createdAt, "1y");
        assertThat(ind.horizonElapsed()).isFalse();
        assertThat(ind.firedRules()).doesNotContain(ExitRules.TIME_STOP);
    }

    @Test void emptyBarsDegradesGracefully() {
        var ind = withTa(ExitTa.unavailable()).compute("AAPL", List.of(), new BigDecimal("10"), null, null);
        assertThat(ind.currentClose()).isNull();
        assertThat(ind.atrAvailable()).isFalse();
        assertThat(ind.firedRules()).isEmpty();
    }
}
