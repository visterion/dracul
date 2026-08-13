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

    // healthy financials: 52w-low 10, ROA 5%, low leverage, positive FCF, cheap P/B (1.2),
    // market cap 900 (Finnhub USD millions)
    private static BasicFinancials healthy(double low) {
        return new BasicFinancials(low, low * 4, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, 900.0);
    }

    /** Convenience for tests that only care about symbol/price/financials. */
    private static LazarusRaw raw(String symbol, double price, BasicFinancials financials) {
        return new LazarusRaw(symbol, symbol + " Inc", price, financials);
    }

    /** Minimal financials with only the fields a given test's gate cares about set explicitly. */
    private static BasicFinancials financials(double week52Low, double week52High, double roaTtm,
            double debtToEquity, double priceToBook) {
        return new BasicFinancials(week52Low, week52High, roaTtm, 1.8, debtToEquity,
                35.0, 8.0, 4.0, 3.0, priceToBook, 11.0, null, null);
    }

    /**
     * Financials for the mega-cap exemption tests: priced exactly at its 52-week low (100.0),
     * healthy solvency (ROA 5%) unless overridden, low leverage, given priceToBook/fcfPerShare/
     * marketCap/reportingCurrency.
     */
    private static BasicFinancials financialsAtLow(Double priceToBook, Double fcfPerShare,
            Double marketCap, String reportingCurrency) {
        return financialsAtLow(priceToBook, fcfPerShare, marketCap, reportingCurrency, 5.0);
    }

    private static BasicFinancials financialsAtLow(Double priceToBook, Double fcfPerShare,
            Double marketCap, String reportingCurrency, double roaTtm) {
        return new BasicFinancials(100.0, 180.0, roaTtm, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, priceToBook, 11.0, fcfPerShare, marketCap, reportingCurrency);
    }

    @Test
    void nearLowAndHealthyPasses() {
        var raws = List.of(new LazarusRaw("ACME", "Acme Inc", 10.50, healthy(10.0)));
        List<LazarusCandidate> out = screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).symbol()).isEqualTo("ACME");
        assertThat(out.get(0).pctAboveLow()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(out.get(0).marketCap()).isEqualTo(900.0); // carried through for the Z enrichment
    }

    @Test
    void aboveThresholdRejected() {
        var raws = List.of(new LazarusRaw("FAR", "Far Inc", 12.00, healthy(10.0)));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).isEmpty();
    }

    @Test
    void insolventRejected() {
        var sick = new BasicFinancials(10.0, 40.0, -3.0, 0.5, 0.4,
                10.0, -5.0, -8.0, -9.0, 0.6, null, -1.0, null);
        var raws = List.of(new LazarusRaw("SICK", "Sick Inc", 10.50, sick));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).isEmpty();
    }

    @Test
    void bothSolvencyMetricsNullRejected() {
        var blackBox = new BasicFinancials(10.0, 40.0, null, 1.5, 0.3,
                null, null, null, null, null, null, null, null);
        var raws = List.of(new LazarusRaw("BLK", "BlackBox Inc", 10.50, blackBox));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).isEmpty();
    }

    @Test
    void highLeverageRejected() {
        var levered = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 5.0,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, null);
        var raws = List.of(new LazarusRaw("LEV", "Levered Inc", 10.50, levered));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).isEmpty();
    }

    @Test
    void nullLeverageNotExcluded() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, null,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, null);
        var raws = List.of(new LazarusRaw("NLV", "NoLeverage Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).hasSize(1);
    }

    @Test
    void nullFinancialsSkipped() {
        var raws = List.of(new LazarusRaw("NUL", "Null Inc", 10.50, null));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).isEmpty();
    }

    @Test
    void missingOrZeroLowSkipped() {
        var noLow = new BasicFinancials(null, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, null);
        var raws = List.of(new LazarusRaw("NLO", "NoLow Inc", 10.50, noLow));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).isEmpty();
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertThat(screener.screen(List.of(), MAX_ABOVE_LOW, MAX_DE, MAX_PB, MAX_PFCF).candidates()).isEmpty();
    }

    // --- valuation (cheapness) gate ---

    @Test
    void cheapByPriceToBookPasses() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 1.2, 11.0, null, null);
        var raws = List.of(new LazarusRaw("PB", "PriceToBook Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, MAX_PFCF).candidates()).hasSize(1);
    }

    @Test
    void cheapByPriceToFcfPassesWhenPriceToBookNull() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, null, 11.0, 2.0, null);
        // currentPrice / fcfPerShare = 10.50 / 2.0 = 5.25 <= maxPFcf(6.0)
        var raws = List.of(new LazarusRaw("PFCF", "PriceToFcf Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, 6.0).candidates()).hasSize(1);
    }

    @Test
    void expensivePriceToBookAndNullFcfRejected() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 5.0, 11.0, null, null);
        var raws = List.of(new LazarusRaw("EXP", "Expensive Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, 6.0).candidates()).isEmpty();
    }

    @Test
    void bothValuationMetricsNullRejected() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, null, 11.0, null, null);
        var raws = List.of(new LazarusRaw("NOVAL", "NoValuation Inc", 10.50, f));
        assertThat(screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 1.5, 6.0).candidates()).isEmpty();
    }

    // --- unit plausibility guard (Task 1) ---

    @Test
    void priceBelowThe52WeekLow_isAUnitError_notACandidate() {
        // Synthetic, modelled on a class of real unit mismatch (a price quoted in one
        // share-class unit against a 52-week low quoted in another): the magnitudes here
        // are invented round numbers, not a production transcript.
        var raw = raw("SYNBRK", 500.00, financials(700000.0, 800000.0, 5.0, 0.2, 1.5));
        var result = screener.screen(List.of(raw), 0.10, 3.0, 2.0, 20);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.implausibleRange()).isEqualTo(1);
    }

    @Test
    void priceExactlyAtTheLow_isStillACandidate() {
        var raw = raw("SYNOK", 100.00, financials(100.00, 180.0, 5.0, 0.5, 1.2));
        var result = screener.screen(List.of(raw), 0.10, 3.0, 2.0, 20);
        assertThat(result.candidates()).hasSize(1);      // pctAboveLow == 0 is still valid
        assertThat(result.implausibleRange()).isZero();
    }

    // --- two-stage cheapness gate: screener forwards the size HOPE, never decides size ---

    @Test
    void passesCheapGate_marksCheapGatePassed() {
        var f = new BasicFinancials(10.0, 40.0, 5.0, 1.8, 0.4,
                35.0, 8.0, 4.0, 3.0, 1.0, 11.0, null, null);
        var raws = List.of(new LazarusRaw("CHEAP", "Cheap Inc", 10.50, f));
        var result = screener.screen(raws, MAX_ABOVE_LOW, MAX_DE, 2.0, MAX_PFCF);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).cheapGatePassed()).isTrue();
    }

    @Test
    void failsCheapGateButHasMarketCap_passesAsSizeHope() {
        var f = financialsAtLow(9.0, null, 500_000.0, null);
        var raw = raw("SYNHOPE", 100.0, f);
        var result = screener.screen(List.of(raw), 0.10, 3.0, 2.0, 20);
        assertThat(result.candidates()).extracting(LazarusCandidate::symbol).containsExactly("SYNHOPE");
        assertThat(result.candidates().get(0).cheapGatePassed()).isFalse();
    }

    @Test
    void failsCheapGateWithoutMarketCap_isDropped() {
        var f = financialsAtLow(9.0, null, null, null);
        var raw = raw("SYNDROP", 100.0, f);
        var result = screener.screen(List.of(raw), 0.10, 3.0, 2.0, 20);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void screenerNeverResolvesListing() {
        var f = financialsAtLow(9.0, null, 500_000.0, "EUR");
        var raw = raw("SYNEUR", 100.0, f);
        var result = screener.screen(List.of(raw), 0.10, 3.0, 2.0, 20);
        assertThat(result.candidates()).extracting(LazarusCandidate::listingResolution)
                .containsExactly(ListingResolution.UNKNOWN);
    }

    @Test
    void hugeRawMarketCapNoLongerAdmitsOnItsOwn() {
        // A market cap this large used to trip the screener's own mega-cap threshold. Now the
        // screener makes no size decision at all — it only forwards the hope (cheapGatePassed
        // stays false); the controller decides size after resolving the listing (Task 3/4).
        var raw = raw("SYNHUGE", 100.0, financialsAtLow(6.26, null, 6_000_000.0, null));
        var result = screener.screen(List.of(raw), 0.10, 3.0, 2.0, 20);
        assertThat(result.candidates()).extracting(LazarusCandidate::symbol).containsExactly("SYNHUGE");
        assertThat(result.candidates().get(0).cheapGatePassed()).isFalse();
    }

    @Test
    void theSizeHopeCoversOnlyCheapness_neverQuality() {
        // A market cap is present, but ROA <= 0 and no FCF: stays out like any other name — the
        // solvency gate runs upstream of the cheapness gate and is untouched by this change.
        var raw = raw("SYNBAD", 100.0, financialsAtLow(6.26, null, 3_712_698.0, null, -5.48));
        var result = screener.screen(List.of(raw), 0.10, 3.0, 2.0, 20);
        assertThat(result.candidates()).isEmpty();
    }
}
