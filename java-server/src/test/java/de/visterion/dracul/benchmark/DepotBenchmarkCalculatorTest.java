package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static de.visterion.dracul.benchmark.BenchmarkFixtures.series;
import static org.assertj.core.api.Assertions.assertThat;

class DepotBenchmarkCalculatorTest {

    private static final PriceSeries EUNL = series(d(2026, 2, 2), d(2026, 3, 13), "100",
            Map.of(d(2026, 3, 6), "101", d(2026, 3, 10), "102"));
    private static final PriceSeries USD = series(d(2026, 2, 2), d(2026, 3, 13), "0.90",
            Map.of(d(2026, 3, 6), "0.92"));
    private static final PriceSeries SYNE = series(d(2026, 3, 2), d(2026, 3, 10), "50",
            Map.of(d(2026, 3, 10), "55"));

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void capitalWeightedEurReturnPerStrigoiAgainstEunlOverEachPositionsWindow() {
        var closedUsd = new DepotBenchmarkCalculator.PositionInput("strigoi-syna", "SYNA", "BUY", bd("10"),
                bd("100"), d(2026, 3, 2), bd("110"), d(2026, 3, 6));
        var openEur = new DepotBenchmarkCalculator.PositionInput("strigoi-syna", "SYNE.DE", "BUY", bd("20"),
                bd("50"), d(2026, 3, 2), null, null);
        var unknown = new DepotBenchmarkCalculator.PositionInput("strigoi-synb", "SYNX.ZZ", "BUY", bd("1"),
                bd("10"), d(2026, 3, 2), bd("11"), d(2026, 3, 6));

        List<StrigoiDepotBenchmark> rows = DepotBenchmarkCalculator.byStrigoi(List.of(closedUsd, openEur, unknown),
                sym -> "SYNE.DE".equals(sym) ? SYNE : PriceSeries.empty(),
                cur -> "USD".equals(cur) ? USD : null, EUNL);

        assertThat(rows).extracting(StrigoiDepotBenchmark::strigoi).containsExactly("strigoi-syna", "strigoi-synb");
        StrigoiDepotBenchmark a = rows.getFirst();
        // w1 = 10*100*0.90 = 900, r1 = 1.1*0.92/0.90-1 = 0.124444, b1 = 101/100-1 = 0.01
        // w2 = 20*50 = 1000,      r2 = 55/50-1 = 0.10,           b2 = 102/100-1 = 0.02
        assertThat(a.positions()).isEqualTo(2);
        assertThat(a.openPositions()).isEqualTo(1);
        assertThat(a.capitalEur()).isEqualByComparingTo("1900.00");
        assertThat(a.returnEur().doubleValue()).isCloseTo(212.0 / 1900, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(a.benchmarkReturn().doubleValue()).isCloseTo(29.0 / 1900, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(a.excess().doubleValue()).isCloseTo(183.0 / 1900, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(a.skipped()).isZero();

        StrigoiDepotBenchmark b = rows.get(1);
        assertThat(b.skipped()).isEqualTo(1);
        assertThat(b.returnEur()).isNull();
        assertThat(b.capitalEur()).isEqualByComparingTo("0");
    }

    @Test
    void anOpenPositionWithoutBarsOrAMissingFxIsSkipped() {
        var open = new DepotBenchmarkCalculator.PositionInput("strigoi-syna", "SYNA", "BUY", bd("1"), bd("10"),
                d(2026, 3, 2), null, null);
        List<StrigoiDepotBenchmark> rows = DepotBenchmarkCalculator.byStrigoi(List.of(open),
                sym -> PriceSeries.empty(), cur -> USD, EUNL);
        assertThat(rows.getFirst().skipped()).isEqualTo(1);

        var closed = new DepotBenchmarkCalculator.PositionInput("strigoi-syna", "SYNA", "BUY", bd("1"), bd("10"),
                d(2026, 3, 2), bd("11"), d(2026, 3, 6));
        assertThat(DepotBenchmarkCalculator.byStrigoi(List.of(closed), sym -> PriceSeries.empty(), cur -> null, EUNL)
                .getFirst().skipped()).isEqualTo(1);
    }
}
