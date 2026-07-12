package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for the deterministic spin lifecycle transitions. The repository and market-data
 *  facade are mocked; each test drives one transition edge case with a fixed "today". */
class SpinLifecycleReconcilerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 12);
    private static final int ABANDON_AFTER = 180;

    private final SpinCandidateRepository repo = mock(SpinCandidateRepository.class);
    private final AgoraMarketData marketData = mock(AgoraMarketData.class);
    private final SpinLifecycleReconciler reconciler =
            new SpinLifecycleReconciler(repo, marketData, ABANDON_AFTER);

    /** Row builder with the fields the reconciler reads; everything else null/default. */
    private static SpinCandidateRow row(long id, SpinStatus status, String symbol,
                                        LocalDate recordDate, LocalDate distributionDate,
                                        String discoveredAt, String distributedAt) {
        return new SpinCandidateRow(id, "cik" + id, symbol, "Co " + id, "10-12B", null, null,
                null, recordDate, distributionDate, false, null, null, status,
                null, null, null, null, null, discoveredAt, discoveredAt, distributedAt, null, null);
    }

    private void nonTerminal(SpinCandidateRow... rows) {
        when(repo.findNonTerminalOldestCheckedFirst(anyInt())).thenReturn(List.of(rows));
    }

    @Test
    void registeredWithoutRecordDateStaysRegistered() {
        nonTerminal(row(1, SpinStatus.REGISTERED, "", null, null, "2026-07-01T00:00:00Z", null));
        when(marketData.quotes(any())).thenReturn(Map.of());

        var result = reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(anyLong(), any(), eq(SpinStatus.WHEN_ISSUED));
        assertThat(result.transitionedIds()).isEmpty();
    }

    @Test
    void recordDateReachedMovesToWhenIssued() {
        nonTerminal(row(1, SpinStatus.REGISTERED, "", LocalDate.of(2026, 7, 1), null,
                "2026-06-25T00:00:00Z", null));
        when(marketData.quotes(any())).thenReturn(Map.of());
        when(repo.advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.WHEN_ISSUED)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.WHEN_ISSUED);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void recordDateReachedButDistributionPastDoesNotMoveToWhenIssued() {
        // distribution already in the past -> the WHEN_ISSUED window is closed
        nonTerminal(row(1, SpinStatus.REGISTERED, "", LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 20), "2026-05-25T00:00:00Z", null));
        when(marketData.quotes(any())).thenReturn(Map.of());

        reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.WHEN_ISSUED);
    }

    @Test
    void abandonsWhenPastThreshold() {
        // discovered 200 days before today, still REGISTERED
        nonTerminal(row(1, SpinStatus.REGISTERED, "", null, null,
                TODAY.minusDays(200).atStartOfDay() + "Z", null));
        when(marketData.quotes(any())).thenReturn(Map.of());
        when(repo.advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.ABANDONED)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.ABANDONED);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void abandonThresholdBoundaryNotYetAbandoned() {
        // discovered exactly ABANDON_AFTER days ago -> plusDays(180) == today, not strictly before
        nonTerminal(row(1, SpinStatus.REGISTERED, "", null, null,
                TODAY.minusDays(ABANDON_AFTER).atStartOfDay() + "Z", null));
        when(marketData.quotes(any())).thenReturn(Map.of());

        reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.ABANDONED);
    }

    @Test
    void positiveQuoteMovesToDistributed() {
        nonTerminal(row(1, SpinStatus.REGISTERED, "SPN", null, null, "2026-07-01T00:00:00Z", null));
        when(marketData.quotes(any()))
                .thenReturn(Map.of("SPN", new Quote(new BigDecimal("12.50"), BigDecimal.ZERO)));
        when(repo.advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void quoteSourceDownSkipsDistributedTransition() {
        // quotes() swallows an outage into an empty map -> no transition, no abort
        nonTerminal(row(1, SpinStatus.REGISTERED, "SPN", null, null, "2026-07-01T00:00:00Z", null));
        when(marketData.quotes(any())).thenReturn(Map.of());

        var result = reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(anyLong(), any(), eq(SpinStatus.DISTRIBUTED));
        assertThat(result.transitionedIds()).isEmpty();
    }

    @Test
    void zeroPriceQuoteDoesNotDistribute() {
        nonTerminal(row(1, SpinStatus.REGISTERED, "SPN", null, null, "2026-07-01T00:00:00Z", null));
        when(marketData.quotes(any()))
                .thenReturn(Map.of("SPN", new Quote(BigDecimal.ZERO, BigDecimal.ZERO)));

        reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(anyLong(), any(), eq(SpinStatus.DISTRIBUTED));
    }

    @Test
    void casGuardPreventsRegressionOnDuplicateReconcile() {
        // repo reports the guarded UPDATE moved nothing (row already advanced) -> not counted
        nonTerminal(row(1, SpinStatus.REGISTERED, "SPN", LocalDate.of(2026, 7, 1), null,
                "2026-06-25T00:00:00Z", null));
        when(marketData.quotes(any())).thenReturn(Map.of());
        when(repo.advanceStatus(1, SpinStatus.REGISTERED, SpinStatus.WHEN_ISSUED)).thenReturn(false);

        var result = reconciler.reconcile(TODAY);

        assertThat(result.transitionedIds()).isEmpty();
    }

    @Test
    void isSettledTrueWhenStandaloneReportFiledAfterDistribution() {
        SpinCandidateRow row = row(1, SpinStatus.DISTRIBUTED, "SPN", null,
                LocalDate.of(2026, 3, 1), "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z");
        ConceptSeries assets = new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, LocalDate.of(2026, 3, 31), new BigDecimal("100"),
                        LocalDate.of(2026, 5, 10))));

        // pure predicate: true and NO status write (the CAS is deferred to advanceToSettled)
        assertThat(reconciler.isSettled(row, assets, TODAY)).isTrue();
        verify(repo, never()).advanceStatus(anyLong(), any(), eq(SpinStatus.SETTLED));
    }

    @Test
    void isSettledFalseWhenOnlyPreDistributionFacts() {
        SpinCandidateRow row = row(1, SpinStatus.DISTRIBUTED, "SPN", null,
                LocalDate.of(2026, 3, 1), "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z");
        // filed + periodEnd both before the distribution date -> not a standalone post-spin report
        ConceptSeries assets = new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, LocalDate.of(2026, 1, 31), new BigDecimal("100"),
                        LocalDate.of(2026, 2, 10))));

        assertThat(reconciler.isSettled(row, assets, TODAY)).isFalse();
    }

    @Test
    void isSettledFalseWithNoDistributionDate() {
        // neither term-sheet distribution_date nor distributed_at known -> cannot judge
        SpinCandidateRow row = row(1, SpinStatus.DISTRIBUTED, "SPN", null, null,
                "2026-01-01T00:00:00Z", null);
        ConceptSeries assets = new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, LocalDate.of(2026, 6, 30), new BigDecimal("100"),
                        LocalDate.of(2026, 7, 5))));

        assertThat(reconciler.isSettled(row, assets, TODAY)).isFalse();
    }

    @Test
    void isSettledFallsBackToDistributedAtWhenNoTermSheetDate() {
        // distribution_date null, distributed_at present -> settlement judged against the stamp
        SpinCandidateRow row = row(1, SpinStatus.DISTRIBUTED, "SPN", null, null,
                "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z");
        ConceptSeries assets = new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, LocalDate.of(2026, 3, 31), new BigDecimal("100"),
                        LocalDate.of(2026, 5, 10))));

        assertThat(reconciler.isSettled(row, assets, TODAY)).isTrue();
    }

    @Test
    void advanceToSettledDelegatesGuardedCasToRepository() {
        when(repo.advanceStatus(7, SpinStatus.DISTRIBUTED, SpinStatus.SETTLED)).thenReturn(true);
        assertThat(reconciler.advanceToSettled(7)).isTrue();
        verify(repo).advanceStatus(7, SpinStatus.DISTRIBUTED, SpinStatus.SETTLED);
    }
}
