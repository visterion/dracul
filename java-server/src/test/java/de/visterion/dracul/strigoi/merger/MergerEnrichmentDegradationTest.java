package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.marketdata.AgoraMarketData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * D5 + D6: the merger enrichment must have a channel back to the fetch health.
 *
 * <p>D5 — the candidate cap silently dropped candidates. EFTS returns file_date DESC, so the cut
 * always kept the newest N and set no flag: a 45-day and a 90-day window both collapsed to the
 * same 25 rows. A cut must surface as {@code truncated}, the way the echo candidate cap does.
 *
 * <p>D6 — a failed {@code get_filing_text} was invisible. Six DEFM14A proxies failed on every
 * production run, the deal terms went unparsed for exactly those six, and the fetch still
 * reported {@code partial=false truncated=false status=healthy}.
 */
class MergerEnrichmentDegradationTest {

    private static MergerCandidate candidate(String sym) {
        return new MergerCandidate(sym, sym + " Corp", "DEFM14A", "2026-05-20", "https://sec/" + sym);
    }

    private static List<MergerCandidate> candidates(int n) {
        return IntStream.range(0, n).mapToObj(i -> candidate("S" + i)).toList();
    }

    private static MergerEnrichmentService service(AgoraFilings filings, AgoraMarketData md, int max) {
        return new MergerEnrichmentService(filings, md, new DealTermsParser(), max,
                MergerEnrichmentService.DEFAULT_DIGEST_CHARS);
    }

    // --- D5: the cap is reported --------------------------------------------------------------

    @Test void capCutsAndReportsTruncated() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText("terms", true));
        when(md.quotes(any())).thenReturn(Map.of());

        EnrichedMergerBatch batch = service(filings, md, 3).enrich(candidates(10));

        assertThat(batch.candidates()).hasSize(3);
        assertThat(batch.truncated()).isTrue();
    }

    @Test void anUncutBatchIsNotTruncated() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(filings.filingText(any())).thenReturn(new FilingText("terms", true));
        when(md.quotes(any())).thenReturn(Map.of());

        EnrichedMergerBatch batch = service(filings, md, 25).enrich(candidates(3));

        assertThat(batch.candidates()).hasSize(3);
        assertThat(batch.truncated()).isFalse();
        assertThat(batch.filingTextFailures()).isZero();
    }

    // --- D6: failed term-sheet fetches are counted, by kind ------------------------------------

    @Test void countsFailedTermSheetsSeparatelyFromOversizedOnes() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(md.quotes(any())).thenReturn(Map.of());
        when(filings.filingText(eq("https://sec/S0"))).thenReturn(new FilingText("terms", true));
        when(filings.filingText(eq("https://sec/S1"))).thenReturn(FilingText.unavailable());
        when(filings.filingText(eq("https://sec/S2"))).thenReturn(FilingText.tooLarge());

        EnrichedMergerBatch batch = service(filings, md, 25).enrich(candidates(3));

        assertThat(batch.candidates()).hasSize(3);          // candidates are kept regardless
        assertThat(batch.filingTextFailures()).isEqualTo(2); // both kinds count as a failure
        assertThat(batch.oversizedFilings()).isEqualTo(1);   // one of them was a too-big document
    }

    @Test void anUnforeseenThrowFromTheFacadeAlsoCounts() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        AgoraMarketData md = Mockito.mock(AgoraMarketData.class);
        when(md.quotes(any())).thenReturn(Map.of());
        when(filings.filingText(any())).thenThrow(new IllegalStateException("boom"));

        EnrichedMergerBatch batch = service(filings, md, 25).enrich(candidates(2));

        assertThat(batch.candidates()).hasSize(2);
        assertThat(batch.filingTextFailures()).isEqualTo(2);
    }

    // --- the merge into the fetch health -------------------------------------------------------

    @Test void degradationRidesTheFetchHealth() {
        DataSourceHealth merged = StrigoiMergerWebhookController.mergeHealth(
                DataSourceHealth.healthy("agora"),
                new EnrichedMergerBatch(List.of(), true, 6, 2));

        assertThat(merged.isHealthy()).isTrue();     // usable data, not an outage
        assertThat(merged.truncated()).isTrue();
        assertThat(merged.partial()).isTrue();       // incomplete coverage: 6 term sheets missing
        assertThat(merged.detail()).contains("6").contains("2");
    }

    @Test void agoraTruncationAndTheCapAreBothKept() {
        DataSourceHealth agora = DataSourceHealth.degraded("agora", "truncated: more exist", false, true);

        DataSourceHealth merged = StrigoiMergerWebhookController.mergeHealth(
                agora, new EnrichedMergerBatch(List.of(), true, 0, 0));

        assertThat(merged.truncated()).isTrue();
        assertThat(merged.detail()).contains("truncated: more exist");
        assertThat(merged.detail()).contains("max-candidates");
    }

    @Test void anOutageIsPassedThroughUntouched() {
        DataSourceHealth down = DataSourceHealth.unavailable("agora", "agora: down");

        DataSourceHealth merged = StrigoiMergerWebhookController.mergeHealth(
                down, new EnrichedMergerBatch(List.of(), true, 3, 0));

        assertThat(merged).isSameAs(down);           // never upgraded into a usable result
        assertThat(merged.isHealthy()).isFalse();
    }

    @Test void aCleanBatchLeavesHealthExactlyAsItWas() {
        DataSourceHealth agora = DataSourceHealth.healthy("agora");

        DataSourceHealth merged = StrigoiMergerWebhookController.mergeHealth(
                agora, new EnrichedMergerBatch(List.of(), false, 0, 0));

        assertThat(merged).isSameAs(agora);
    }
}
