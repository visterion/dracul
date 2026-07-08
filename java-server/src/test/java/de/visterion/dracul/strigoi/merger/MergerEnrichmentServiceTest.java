package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md).enrich(List.of(candidate("TGT")));

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

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md).enrich(List.of(candidate("ABC")));

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

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md).enrich(List.of(candidate("XYZ")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).priceAvailable()).isFalse();
    }

    @Test void zeroPriceIsTreatedAsUnavailable() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText("t", true));
        // quotes() maps a missing/malformed price to BigDecimal.ZERO.
        when(md.quotes(any())).thenReturn(Map.of("ZRO", new Quote(BigDecimal.ZERO, BigDecimal.ZERO)));

        List<EnrichedMergerCandidate> out = new MergerEnrichmentService(filings, md).enrich(List.of(candidate("ZRO")));

        assertThat(out.get(0).lastPrice()).isNull();
        assertThat(out.get(0).priceAvailable()).isFalse();
    }
}
