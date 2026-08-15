package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
    /** The 7-day due-check is anchored on the {@code today} seam (fix-round-1, Minor 3), not on
     *  wall-clock {@link Instant#now()} — so termsCheckedAt fixtures are relative to THIS, not to
     *  real time, or the test would be flaky depending on when it happens to run. */
    private static final Instant TODAY_START_UTC = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant();

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
        when(repo.findNonTerminalOldestCheckedFirst(anyInt())).thenReturn(List.of(rows));
    }

    /** Stubs the DISTRIBUTED-stage switch dependencies so a row's status processing doesn't throw
     *  and pollute the assertions this test actually cares about. */
    private void distributedSwitchIsHarmless(SpinCandidateRow row) {
        when(filings.conceptStrict(eq(row.symbol()), eq(row.cik()), eq("Assets")))
                .thenReturn(de.visterion.dracul.hunting.agora.ConceptSeries.empty("Assets"));
    }

    @Test
    void capturesTermsForADistributedRow() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, null, false, null, null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("prose", true, FilingText.Failure.NONE, "EX-99.1"));
        when(termsParser.parse(any())).thenReturn(new SpinTerms("one for two", null, null));
        when(termsParser.parentTicker(any())).thenReturn(null);
        distributedSwitchIsHarmless(r);

        enricher.enrich(noTransitions(), TODAY);

        verify(filings).filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING"));
        verify(repo).storeTerms(1L, "one for two", null, null, true, "prose", null);
    }

    /**
     * fix-round-1, Important 2: the shape of the five actual production rows (ADIG, HONA, MFP,
     * MBGL, BSEM) — DISTRIBUTED, ALREADY has {@code term_sheet_available=true} and a non-null
     * ratio, no parsed dates. Every other test in this file with {@code termSheetAvailable=true}
     * asserts a NEGATIVE path (storeTerms must NOT fire); none of them would catch a regression
     * that made {@link SpinCandidateEnricher#captureTerms} inert again for exactly this shape
     * (e.g. resurrecting the old {@code !row.termSheetAvailable()} clause). This test is the one
     * that actually pins the POSITIVE case this task exists for: once D1 lands and the fetch
     * resolves the requested EX-99.1 exhibit, the row's stale term sheet IS replaced.
     */
    @Test
    void capturesTheNewExhibitForAProdShapedDistributedRowWithExistingRatioAndText() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, "one for two", true, "stale Form-10 shell text", null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("fresh EX-99.1 information statement prose", true,
                        FilingText.Failure.NONE, "EX-99.1"));
        when(termsParser.parse(any())).thenReturn(new SpinTerms("one for three", null, null));
        when(termsParser.parentTicker(any())).thenReturn(null);
        distributedSwitchIsHarmless(r);

        enricher.enrich(noTransitions(), TODAY);

        verify(repo).storeTerms(1L, "one for three", null, null, true,
                "fresh EX-99.1 information statement prose", null);
    }

    @Test
    void doesNotOverwriteExistingTermsWhenTheFetchFailed() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, "ratio", true, "existing text", null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenThrow(new RuntimeException("agora down"));
        distributedSwitchIsHarmless(r);

        enricher.enrich(noTransitions(), TODAY);

        verify(repo, never()).storeTerms(eq(1L), any(), any(), any(), anyBoolean(), any(), any());
        verify(repo).touchTermsChecked(1L);
    }

    @Test
    void doesNotOverwriteGoodTermsWithShellTextWhenTheExhibitWasNotResolved() {
        SpinCandidateRow r = row(1, SpinStatus.DISTRIBUTED, "ratio", true, "existing good text", null);
        queue(r);
        when(filings.filingText(eq(r.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("shell text", true, FilingText.Failure.NONE, null));
        distributedSwitchIsHarmless(r);

        enricher.enrich(noTransitions(), TODAY);

        verify(repo, never()).storeTerms(eq(1L), any(), any(), any(), anyBoolean(), any(), any());
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
        distributedSwitchIsHarmless(r);

        enricher.enrich(noTransitions(), TODAY);

        verify(repo).storeTerms(1L, null, null, null, true, "shell text", null);
    }

    @Test
    void skipsCaptureWithinSevenDaysOfTheLastAttempt() {
        SpinCandidateRow fresh = row(1, SpinStatus.DISTRIBUTED, null, false, null,
                TODAY_START_UTC.minus(3, ChronoUnit.DAYS));
        queue(fresh);
        distributedSwitchIsHarmless(fresh);

        enricher.enrich(noTransitions(), TODAY);

        verify(filings, never()).filingText(any(), any(), any());
    }

    @Test
    void retriesCaptureAfterSevenDays() {
        SpinCandidateRow stale = row(1, SpinStatus.DISTRIBUTED, null, false, null,
                TODAY_START_UTC.minus(8, ChronoUnit.DAYS));
        queue(stale);
        when(filings.filingText(eq(stale.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(FilingText.unavailable());
        distributedSwitchIsHarmless(stale);

        enricher.enrich(noTransitions(), TODAY);

        verify(filings).filingText(eq(stale.filingUrl()), eq("EX-99.1"), eq("LEADING"));
    }

    /**
     * fix-round-1, Important 1: a THREE-row probe, not two — with only two rows the gap is
     * invisible (row 1 trips the guard, row 2 happens to still get captured because the OLD
     * {@code break} only ever skipped what came strictly AFTER the row that flipped
     * {@code health.skipAll()} — but a two-row queue has no "after" left to skip). Row 3 is the
     * row that actually proves the loop kept going rather than terminating early.
     */
    @Test
    void capturesEvenWhenBothStrictSourcesAreDown() {
        SpinCandidateRow first = row(1, SpinStatus.DISTRIBUTED, "ratio", true, "already has text", null);
        SpinCandidateRow second = row(2, SpinStatus.DISTRIBUTED, null, false, null, null);
        SpinCandidateRow third = row(3, SpinStatus.DISTRIBUTED, null, false, null, null);
        queue(first, second, third);

        // First row: term capture is a no-op (already has text, fetch unavailable is fine too),
        // but its settlement probe throws AgoraUnavailableException to mark the concept source
        // down, and the owner-history fetch throws to mark that one down too — BOTH strict
        // sources are now down after this row.
        when(filings.filingText(eq(first.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(FilingText.unavailable());
        when(filings.conceptStrict(eq(first.symbol()), eq(first.cik()), eq("Assets")))
                .thenThrow(new de.visterion.dracul.marketdata.AgoraUnavailableException("down"));
        when(distribution.snapshot(any(), any(), any(), any(), any()))
                .thenThrow(new de.visterion.dracul.marketdata.AgoraUnavailableException("down"));

        // Second and third row: term fetch should still be attempted for BOTH even though both
        // strict sources are already down when the loop reaches them.
        when(filings.filingText(eq(second.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("captured2", true, FilingText.Failure.NONE, "EX-99.1"));
        when(filings.filingText(eq(third.filingUrl()), eq("EX-99.1"), eq("LEADING")))
                .thenReturn(new FilingText("captured3", true, FilingText.Failure.NONE, "EX-99.1"));
        when(termsParser.parse(any())).thenReturn(new SpinTerms("some ratio", null, null));
        when(termsParser.parentTicker(any())).thenReturn(null);

        enricher.enrich(noTransitions(), TODAY);

        verify(filings).filingText(eq(second.filingUrl()), eq("EX-99.1"), eq("LEADING"));
        verify(filings).filingText(eq(third.filingUrl()), eq("EX-99.1"), eq("LEADING"));
        verify(repo).storeTerms(2L, "some ratio", null, null, true, "captured2", null);
        verify(repo).storeTerms(3L, "some ratio", null, null, true, "captured3", null);
        // The strict-source work itself must actually be skipped for both — not merely "still
        // ends up false somehow" — or this test would pass for the wrong reason.
        verify(filings, never()).conceptStrict(eq(second.symbol()), any(), eq("Assets"));
        verify(filings, never()).conceptStrict(eq(third.symbol()), any(), eq("Assets"));
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
        verify(repo, never()).storeTerms(eq(1L), any(), any(), any(), anyBoolean(), any(), any());
    }
}
