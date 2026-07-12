package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.marketdata.Quote;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergerEnrichmentServiceTest {

    private MergerCandidate candidate(String sym) {
        return new MergerCandidate(sym, sym + " Corp", "DEFM14A", "2026-05-20", "https://sec/" + sym);
    }

    @Test void threadsTermSheetAndPrice() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText("https://sec/TGT")).thenReturn(new FilingText("SUMMARY TERM SHEET $52 cash", true));
        when(md.quotes(any())).thenReturn(Map.of("TGT", new Quote(new BigDecimal("47.50"), BigDecimal.ZERO)));

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT")));

        assertThat(out).hasSize(1);
        EnrichedMergerCandidate e = out.get(0);
        assertThat(e.symbol()).isEqualTo("TGT");
        assertThat(e.termSheet()).contains("$52 cash");
        assertThat(e.termSheetAvailable()).isTrue();
        assertThat(e.lastPrice()).isEqualByComparingTo("47.50");
        assertThat(e.priceAvailable()).isTrue();
    }

    @Test void degradesFieldsWhenLookupsFail() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(FilingText.unavailable());
        when(md.quotes(any())).thenReturn(Map.of());   // no price

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md, new DealTermsParser()).enrich(List.of(candidate("ABC")));

        EnrichedMergerCandidate e = out.get(0);
        assertThat(e.termSheetAvailable()).isFalse();
        assertThat(e.termSheet()).isEmpty();
        assertThat(e.lastPrice()).isNull();
        assertThat(e.priceAvailable()).isFalse();
    }

    @Test void neverThrowsWhenMarketDataThrows() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText("t", true));
        when(md.quotes(any())).thenThrow(new RuntimeException("boom"));

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md, new DealTermsParser()).enrich(List.of(candidate("XYZ")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).priceAvailable()).isFalse();
    }

    @Test void zeroPriceIsTreatedAsUnavailable() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText("t", true));
        // quotes() maps a missing/malformed price to BigDecimal.ZERO.
        when(md.quotes(any())).thenReturn(Map.of("ZRO", new Quote(BigDecimal.ZERO, BigDecimal.ZERO)));

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md, new DealTermsParser()).enrich(List.of(candidate("ZRO")));

        assertThat(out.get(0).lastPrice()).isNull();
        assertThat(out.get(0).priceAvailable()).isFalse();
    }

    @Test void extractsDealTermsAndComputesSpread() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(
                new FilingText("...shareholders will receive $54.20 in cash per share...", true));
        when(md.quotes(any())).thenReturn(Map.of("TGT", new Quote(new BigDecimal("50.00"), BigDecimal.ZERO)));

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT")));

        EnrichedMergerCandidate e = out.get(0);
        assertThat(e.offerPrice()).isEqualByComparingTo("54.20");
        assertThat(e.considerationType()).isEqualTo("cash");
        assertThat(e.spreadPercent()).isEqualByComparingTo("8.40");
    }

    // ---- expected-value fields (unaffected price, days-to-close, annualized spread, break downside) ----

    /** A term sheet with an agreement date and an expected close, priced so every derived
     *  field is exercised. */
    private static final String FULL_TERMS =
            "Agreement and Plan of Merger, dated as of March 15, 2026. Each share of common stock "
            + "will be converted into the right to receive $60.00 in cash per share. The transaction "
            + "is expected to close in the fourth quarter of 2026. Termination fee of approximately $85 million.";

    private static OhlcBar bar(String date, String close) {
        return new OhlcBar(LocalDate.parse(date), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), 1000L);
    }

    @Test void anchorsUnaffectedPriceOnLastBarBeforeAgreementDate() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText(FULL_TERMS, true));
        when(md.quotes(any())).thenReturn(Map.of("TGT", new Quote(new BigDecimal("54.00"), BigDecimal.ZERO)));
        // agreementDate = 2026-03-15; the 03-16 bar is on/after the anchor and must be ignored.
        when(md.dailyOhlcHistory(eq("TGT"), anyInt())).thenReturn(List.of(
                bar("2026-03-12", "40.00"), bar("2026-03-13", "41.90"), bar("2026-03-16", "55.00")));

        EnrichedMergerCandidate e = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT"))).get(0);

        assertThat(e.agreementDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(e.expectedCloseDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(e.unaffectedPrice()).isEqualByComparingTo("41.90"); // last close strictly before 03-15
        assertThat(e.unaffectedPriceAvailable()).isTrue();
        // breakDownside = (54.00 − 41.90) / 54.00 × 100 = 22.41
        assertThat(e.breakDownsidePercent()).isEqualByComparingTo("22.41");
    }

    @Test void annualizedSpreadFollowsTheFormula() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText(FULL_TERMS, true));
        when(md.quotes(any())).thenReturn(Map.of("TGT", new Quote(new BigDecimal("54.00"), BigDecimal.ZERO)));
        when(md.dailyOhlcHistory(any(), anyInt())).thenReturn(List.of(bar("2026-03-13", "41.90")));

        EnrichedMergerCandidate e = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT"))).get(0);

        // spread = (60 − 54) / 54 × 100 = 11.11; recompute annualized off the result's own
        // daysToClose so the assertion is independent of the current date.
        assertThat(e.spreadPercent()).isEqualByComparingTo("11.11");
        assertThat(e.daysToClose()).isNotNull();
        BigDecimal expected = e.spreadPercent().multiply(BigDecimal.valueOf(365))
                .divide(BigDecimal.valueOf(e.daysToClose()), 2, java.math.RoundingMode.HALF_UP);
        assertThat(e.annualizedSpreadPercent()).isEqualByComparingTo(expected);
    }

    @Test void daysToCloseGuardNullsAnnualizedForPastCloseDate() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        // expected close already in the past → daysToClose < 1 → annualized must be null.
        String pastClose = "Merger Agreement dated as of January 5, 2020. Shareholders receive $60.00 "
                + "in cash per share. Expected to close by March 31, 2020.";
        when(filings.filingText(any())).thenReturn(new FilingText(pastClose, true));
        when(md.quotes(any())).thenReturn(Map.of("TGT", new Quote(new BigDecimal("54.00"), BigDecimal.ZERO)));
        when(md.dailyOhlcHistory(any(), anyInt())).thenReturn(List.of());

        EnrichedMergerCandidate e = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT"))).get(0);

        assertThat(e.daysToClose()).isNotNull().isLessThan(1);
        assertThat(e.spreadPercent()).isNotNull();
        assertThat(e.annualizedSpreadPercent()).isNull();
    }

    @Test void noAgreementDateSkipsOhlcAndLeavesUnaffectedUnavailable() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        // Term sheet has a price but no parseable dates.
        when(filings.filingText(any())).thenReturn(
                new FilingText("shareholders will receive $54.20 in cash per share", true));
        when(md.quotes(any())).thenReturn(Map.of("TGT", new Quote(new BigDecimal("50.00"), BigDecimal.ZERO)));

        EnrichedMergerCandidate e = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT"))).get(0);

        assertThat(e.agreementDate()).isNull();
        assertThat(e.unaffectedPriceAvailable()).isFalse();
        assertThat(e.unaffectedPrice()).isNull();
        assertThat(e.breakDownsidePercent()).isNull();
        verify(md, never()).dailyOhlcHistory(any(), anyInt()); // no anchor → no OHLC round trip
    }

    @Test void ohlcOutOfReachDegradesUnaffectedButKeepsCandidate() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText(FULL_TERMS, true));
        when(md.quotes(any())).thenReturn(Map.of("TGT", new Quote(new BigDecimal("54.00"), BigDecimal.ZERO)));
        // All bars are on/after the agreement date → nothing before the anchor.
        when(md.dailyOhlcHistory(any(), anyInt())).thenReturn(List.of(bar("2026-03-16", "55.00")));

        EnrichedMergerCandidate e = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT"))).get(0);

        assertThat(e.unaffectedPriceAvailable()).isFalse();
        assertThat(e.unaffectedPrice()).isNull();
        assertThat(e.breakDownsidePercent()).isNull();
    }

    @Test void availabilityFailureShortCircuitsRestOfBatchThenResetsNextBatch() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText(FULL_TERMS, true));
        when(md.quotes(any())).thenReturn(Map.of(
                "TGT", new Quote(new BigDecimal("54.00"), BigDecimal.ZERO),
                "ABC", new Quote(new BigDecimal("30.00"), BigDecimal.ZERO)));
        when(md.dailyOhlcHistory(any(), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "agora down"));

        MergerEnrichmentService svc = new MergerEnrichmentService(filings, md, new DealTermsParser());

        svc.enrich(List.of(candidate("TGT"), candidate("ABC")));
        // First candidate tripped the source-down guard → the second is skipped this batch.
        verify(md, times(1)).dailyOhlcHistory(any(), anyInt());

        // A fresh batch resets the per-run guard and retries OHLC.
        svc.enrich(List.of(candidate("TGT")));
        verify(md, times(2)).dailyOhlcHistory(any(), anyInt());
    }

    @Test void notFoundDoesNotDisableSourceForRestOfBatch() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText(FULL_TERMS, true));
        when(md.quotes(any())).thenReturn(Map.of(
                "TGT", new Quote(new BigDecimal("54.00"), BigDecimal.ZERO),
                "ABC", new Quote(new BigDecimal("30.00"), BigDecimal.ZERO)));
        // TGT is symbol-specific NOT_FOUND; ABC resolves normally.
        when(md.dailyOhlcHistory(eq("TGT"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no bars"));
        when(md.dailyOhlcHistory(eq("ABC"), anyInt())).thenReturn(List.of(bar("2026-03-13", "20.00")));

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md, new DealTermsParser())
                .enrich(List.of(candidate("TGT"), candidate("ABC")));

        // NOT_FOUND leaves the source up → ABC is still queried and anchored.
        verify(md, times(1)).dailyOhlcHistory(eq("ABC"), anyInt());
        assertThat(out.get(0).unaffectedPriceAvailable()).isFalse();     // TGT
        assertThat(out.get(1).unaffectedPrice()).isEqualByComparingTo("20.00"); // ABC
    }
}
