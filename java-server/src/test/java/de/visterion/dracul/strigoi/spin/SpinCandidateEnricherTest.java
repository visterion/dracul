package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3 (#45): term-sheet capture now runs for REGISTERED, WHEN_ISSUED AND DISTRIBUTED rows —
 * before this fix it only ran in the REGISTERED branch, so five production rows (ADIG, HONA, MFP,
 * MBGL, BSEM), all DISTRIBUTED, were never captured at all and hold the Form-10 shell's text
 * forever. See {@code docs/superpowers/plans/2026-08-15-spin-information-statement.md} Task D3.
 *
 * <p>Every test drives {@link SpinCandidateEnricher#enrich(SpinLifecycleReconciler.ReconcileResult, LocalDate)}
 * directly (package-private date seam) with a single-row queue, so the only observable side effect
 * to assert on is which repository methods fired.
 */
class SpinCandidateEnricherTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-08-15");

    private final SpinCandidateRepository repo = mock(SpinCandidateRepository.class);
    private final SpinLifecycleReconciler reconciler = mock(SpinLifecycleReconciler.class);
    private final SpinBalanceSheetSnapshotter balanceSheet = mock(SpinBalanceSheetSnapshotter.class);
    private final SpinDistributionSnapshotter distribution = mock(SpinDistributionSnapshotter.class);
    private final SpinValuationSnapshotter valuation = mock(SpinValuationSnapshotter.class);
    private final AgoraFilings filings = mock(AgoraFilings.class);
    private final SpinTermsParser termsParser = mock(SpinTermsParser.class);
    private final SpinCandidateEnricher enricher = new SpinCandidateEnricher(
            repo, reconciler, balanceSheet, distribution, valuation, filings, termsParser, new ObjectMapper());

    private static SpinLifecycleReconciler.ReconcileResult noTransitions() {
        return SpinLifecycleReconciler.ReconcileResult.empty();
    }

    /** Row builder exposing only the fields captureTerms()/the enrich loop reads. */
    private static SpinCandidateRow row(long id, SpinStatus status, String distributionRatio,
                                        boolean termSheetAvailable, String termSheetText,
                                        Instant termsCheckedAt) {
        return new SpinCandidateRow(id, "cik" + id, "SYM" + id, "Co " + id, "10-12B",
                LocalDate.parse("2026-07-01"), "https://sec/" + id,
                distributionRatio, null, null, termSheetAvailable, termSheetText, null,
                status, null, null, null,
                null, null, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z",
                status == SpinStatus.DISTRIBUTED ? "2026-08-01T00:00:00Z" : null,
                null, null, termsCheckedAt);
    }

    private void queue(SpinCandidateRow... rows) {
        when(repo.findNonTerminalOldestCheckedFirst(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(rows));
    }

    @Test
    void capturesTermsForADistributedRow() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, null, false, null, null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("prose", true, FilingText.Failure.NONE, "EX-99.1"));
        when(termsParser.parse(any())).thenReturn(new SpinTerms("one for two", null, null));
        when(termsParser.parentTicker(any())).thenReturn(null);
        // DISTRIBUTED-stage switch dependencies, so the run doesn't throw on the rest of the row.
        when(filings.conceptStrict(any(), any(), eq("Assets")))
                .thenReturn(de.visterion.dracul.hunting.agora.ConceptSeries.empty("Assets"));

        enricher.enrich(noTransitions(), TODAY);

        verify(filings).filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING"));
        verify(repo).storeTerms(1L, "one for two", null, null, true, "prose", null);
    }

    @Test
    void doesNotOverwriteExistingTermsWhenTheFetchFailed() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, "ratio", true, "existing text", null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenThrow(new RuntimeException("agora down"));
        when(filings.conceptStrict(any(), any(), eq("Assets")))
                .thenReturn(de.visterion.dracul.hunting.agora.ConceptSeries.empty("Assets"));

        enricher.enrich(noTransitions(), TODAY);

        verify(repo, never()).storeTerms(eq(1L), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        verify(repo).touchTermsChecked(1L);
    }

    @Test
    void doesNotOverwriteGoodTermsWithShellTextWhenTheExhibitWasNotResolved() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, "ratio", true, "existing good text", null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("shell text", true, FilingText.Failure.NONE, null));
        when(filings.conceptStrict(any(), any(), eq("Assets")))
                .thenReturn(de.visterion.dracul.hunting.agora.ConceptSeries.empty("Assets"));

        enricher.enrich(noTransitions(), TODAY);

        verify(repo, never()).storeTerms(eq(1L), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        verify(repo).touchTermsChecked(1L);
    }

    @Test
    void storesTheFallbackTextForAFreshRowWithNoTextYet() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, null, false, null, null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("shell text", true, FilingText.Failure.NONE, null));
        when(termsParser.parse(any())).thenReturn(new SpinTerms(null, null, null));
        when(termsParser.parentTicker(any())).thenReturn(null);
        when(filings.conceptStrict(any(), any(), eq("Assets")))
                .thenReturn(de.visterion.dracul.hunting.agora.ConceptSeries.empty("Assets"));

        enricher.enrich(noTransitions(), TODAY);

        verify(repo).storeTerms(1L, null, null, null, true, "shell text", null);
    }

    @Test
    void skipsCaptureWithinSevenDaysOfTheLastAttempt() {
        SpinCandidateRow fresh = row(1, SpinStatus.DISTRIBUTED, null, false, null,
                Instant.now().minus(3, ChronoUnit.DAYS));
        queue(fresh);
        when(filings.conceptStrict(any(), any(), eq("Assets")))
                .thenReturn(de.visterion.dracul.hunting.agora.ConceptSeries.empty("Assets"));

        enricher.enrich(noTransitions(), TODAY);

        verify(filings, never()).filingText(any(), any(), any());
    }

    @Test
    void retriesCaptureAfterSevenDays() {
        SpinCandidateRow stale = row(1, SpinStatus.DISTRIBUTED, null, false, null,
                Instant.now().minus(8, ChronoUnit.DAYS));
        queue(stale);
        when(filings.filingText(eq(stale.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(FilingText.unavailable());
        when(filings.conceptStrict(any(), any(), eq("Assets")))
                .thenReturn(de.visterion.dracul.hunting.agora.ConceptSeries.empty("Assets"));

        enricher.enrich(noTransitions(), TODAY);

        verify(filings).filingText(eq(stale.filingUrl()), eq("EX-99.1"), eq("LEADING"));
    }

    @Test
    void capturesEvenWhenBothStrictSourcesAreDown() {
        // Two DISTRIBUTED rows: the first trips BOTH strict-source guards (concept AND owner
        // history down), the second must still get its term captured despite health.skipAll().
        SpinCandidateRow first = row(1, SpinStatus.DISTRIBUTED, "ratio", true, "already has text", null);
        SpinCandidateRow second = row(2, SpinStatus.DISTRIBUTED, null, false, null, null);
        queue(first, second);
        // First row: term capture is a no-op (already has text, fetch unavailable is fine too),
        // but its settlement probe throws AgoraUnavailableException to mark the concept source down.
        when(filings.filingText(eq(first.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(FilingText.unavailable());
        when(filings.conceptStrict(eq(first.symbol()), eq(first.cik()), eq("Assets")))
                .thenThrow(new de.visterion.dracul.marketdata.AgoraUnavailableException("down"));
        when(distribution.snapshot(any(), any(), any(), any(), any()))
                .thenThrow(new de.visterion.dracul.marketdata.AgoraUnavailableException("down"));
        // Second row: term fetch should still be attempted even though both strict sources are down.
        when(filings.filingText(eq(second.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("captured", true, FilingText.Failure.NONE, "EX-99.1"));
        when(termsParser.parse(any())).thenReturn(new SpinTerms("ratio2", null, null));
        when(termsParser.parentTicker(any())).thenReturn(null);

        enricher.enrich(noTransitions(), TODAY);

        verify(filings).filingText(eq(second.filingUrl()), eq("EX-99.1"), eq("LEADING"));
        verify(repo).storeTerms(2L, "ratio2", null, null, true, "captured", null);
    }

    @Test
    void stampsTermsCheckedAtWhenTheRowThrows() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, null, false, null, null);
        queue(r);
        // captureTerms itself throws unexpectedly (not the usual fetch-fails-fail-soft path).
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("prose", true, FilingText.Failure.NONE, "EX-99.1"));
        when(termsParser.parse(any())).thenThrow(new RuntimeException("parser blew up"));

        enricher.enrich(noTransitions(), TODAY);

        verify(repo).touchTermsChecked(1L);
        verify(repo).touchLastChecked(1L);
        verify(repo, never()).storeTerms(eq(1L), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }
}
