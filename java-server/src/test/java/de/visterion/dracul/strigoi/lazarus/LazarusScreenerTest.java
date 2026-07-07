package de.visterion.dracul.strigoi.lazarus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazarusScreenerTest {

    private final LazarusScreener screener = new LazarusScreener();
    private static final double MAX_ABOVE_LOW = 0.10;
    private static final double MAX_DE = 3.0;
    // permissive valuation caps for tests that don't exercise the cheapness gate
    private static final double MAX_PB = 1e9;
    private static final double MAX_PFCF = 1e9;

    // healthy financials: 52w-low 10, ROA 5%, low leverage, positive FCF, cheap P/B (1.2)
    private static BasicFinancials healthy(double low) {
        return new BasicFinancials(low, low * 4, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3);
    }

    @Test
    void nearLowAndHealthyPasses() {
        var raws = List.of(new LazarusRaw("ACME", "Acme Inc", 10.50, healthy(10.0)));
        List<LazarusCandidate> out = screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).symbol()).isEqualTo("ACME");
        assertThat(out.get(0).pctAboveLow()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aboveThresholdRejected() {
        var raws = List.of(new LazarusRaw("FAR", "Far Inc", 12.00, healthy(10.0)));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).isEmpty();
    }

    @Test
    void insolventRejected() {
        var sick = new BasicFinancials(10.0, 40.0, -3.0, 0.5, 0.4,
                10.0, -5.0, -8.0, -9.0, 0.6, null, -1.0);
        var raws = List.of(new LazarusRaw("SICK", "Sick Inc", 10.50, sick));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).isEmpty();
    }

    @Test
    void bothSolvencyMetricsNullRejected() {
        var blackBox = new BasicFinancials(10.0, 40.0, null, 1.5, 0.3,
                null, null, null, null, null, null, null);
        var raws = List.of(new LazarusRaw("BLK", "BlackBox Inc", 10.50, blackBox));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).isEmpty();
    }

    @Test
    void highLeverageRejected() {
        var levered = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 5.0,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3);
        var raws = List.of(new LazarusRaw("LEV", "Levered Inc", 10.50, levered));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).isEmpty();
    }

    @Test
    void nullLeverageNotExcluded() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, null,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3);
        var raws = List.of(new LazarusRaw("NLV", "NoLeverage Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).hasSize(1);
    }

    @Test
    void nullFinancialsSkipped() {
        var raws = List.of(new LazarusRaw("NUL", "Null Inc", 10.50, null));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).isEmpty();
    }

    @Test
    void missingOrZeroLowSkipped() {
        var noLow = new BasicFinancials(null, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3);
        var raws = List.of(new LazarusRaw("NLO", "NoLow Inc", 10.50, noLow));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).isEmpty();
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertThat(screener.screen(List.of(), MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF)).isEmpty();
    }

    // --- valuation (cheapness) gate ---

    @Test
    void cheapByPriceToBookPasses() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, null);
        var raws = List.of(new LazarusRaw("PB", "PriceToBook Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, MAX_PFCF)).hasSize(1);
    }

    @Test
    void cheapByPriceToFcfPassesWhenPriceToBookNull() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, null, 11.0, 2.0);
        // currentPrice / fcfPerShare = 10.50 / 2.0 = 5.25 <= maxPFcf(6.0)
        var raws = List.of(new LazarusRaw("PFCF", "PriceToFcf Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, 6.0)).hasSize(1);
    }

    @Test
    void expensivePriceToBookAndNullFcfRejected() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 5.0, 11.0, null);
        var raws = List.of(new LazarusRaw("EXP", "Expensive Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, 6.0)).isEmpty();
    }

    @Test
    void bothValuationMetricsNullRejected() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, null, 11.0, null);
        var raws = List.of(new LazarusRaw("NOVAL", "NoValuation Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, 6.0)).isEmpty();
    }
}
