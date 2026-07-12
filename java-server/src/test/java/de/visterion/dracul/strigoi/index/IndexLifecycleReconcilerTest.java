package de.visterion.dracul.strigoi.index;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for the deterministic, pure-calendar index lifecycle transitions. The repository is
 *  mocked; each test drives one transition edge case with a fixed "today". There are ZERO Agora
 *  calls in this reconciler (unlike spin's quote probe), so no market-data mock is needed. */
class IndexLifecycleReconcilerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 12);
    private static final int OBSERVATION_WINDOW = 30;
    private static final int ABANDON_AFTER = 45;

    private final IndexEventRepository repo = mock(IndexEventRepository.class);
    private final IndexLifecycleReconciler reconciler =
            new IndexLifecycleReconciler(repo, OBSERVATION_WINDOW, ABANDON_AFTER);

    /** Row builder with only the fields the reconciler reads; everything else null/default. */
    private static IndexEventRow row(long id, IndexEventStatus status,
                                     LocalDate announcement, LocalDate effective) {
        return new IndexEventRow(id, "SYM" + id, "Co " + id, "sp500", "add", "sp_press",
                announcement, effective, status,
                null, null, null, null, null, null, null, null, null);
    }

    private void queue(IndexEventRow... rows) {
        when(repo.findNonTerminalOldestCheckedFirst(anyInt())).thenReturn(List.of(rows));
    }

    @Test
    void announcedBeforeEffectiveStaysAnnounced() {
        queue(row(1, IndexEventStatus.ANNOUNCED, TODAY.minusDays(3), TODAY.plusDays(4)));

        var result = reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(anyLong(), any(), any());
        verify(repo).touchLastChecked(1);
        assertThat(result.transitionedIds()).isEmpty();
    }

    @Test
    void announcedOnEffectiveDateMovesToEffective() {
        // today == effective_date -> the effective day has arrived
        queue(row(1, IndexEventStatus.ANNOUNCED, TODAY.minusDays(10), TODAY));
        when(repo.advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE);
        verify(repo, never()).touchLastChecked(1);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void announcedAfterEffectiveMovesToEffective() {
        queue(row(1, IndexEventStatus.ANNOUNCED, TODAY.minusDays(10), TODAY.minusDays(1)));
        when(repo.advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void effectiveMovesToPostUnconditionally() {
        // EFFECTIVE is transient: the next pass always advances it to POST
        queue(row(1, IndexEventStatus.EFFECTIVE, TODAY.minusDays(20), TODAY.minusDays(1)));
        when(repo.advanceStatus(1, IndexEventStatus.EFFECTIVE, IndexEventStatus.POST)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, IndexEventStatus.EFFECTIVE, IndexEventStatus.POST);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void postBeforeObservationWindowStaysPost() {
        // effective 29 days ago, window is 30 -> boundary not yet reached
        queue(row(1, IndexEventStatus.POST, TODAY.minusDays(40), TODAY.minusDays(OBSERVATION_WINDOW - 1)));

        var result = reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(anyLong(), any(), any());
        verify(repo).touchLastChecked(1);
        assertThat(result.transitionedIds()).isEmpty();
    }

    @Test
    void postAtObservationWindowBoundaryMovesToClosed() {
        // effective exactly OBSERVATION_WINDOW days ago -> effective+window == today -> close fires
        queue(row(1, IndexEventStatus.POST, TODAY.minusDays(50), TODAY.minusDays(OBSERVATION_WINDOW)));
        when(repo.advanceStatus(1, IndexEventStatus.POST, IndexEventStatus.CLOSED)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, IndexEventStatus.POST, IndexEventStatus.CLOSED);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void abandonsWhenAnnouncedTooLongWithFutureEffective() {
        // announced 46 days ago (> 45), effective still in the future -> source anomaly
        queue(row(1, IndexEventStatus.ANNOUNCED, TODAY.minusDays(ABANDON_AFTER + 1), TODAY.plusDays(5)));
        when(repo.advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.ABANDONED)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.ABANDONED);
        verify(repo, never()).advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE);
        assertThat(result.transitionedIds()).containsExactly(1L);
    }

    @Test
    void abandonBoundaryNotYetAbandoned() {
        // announced exactly ABANDON_AFTER days ago -> plusDays(45) == today, not strictly before;
        // effective still future -> neither ABANDONED nor EFFECTIVE fires
        queue(row(1, IndexEventStatus.ANNOUNCED, TODAY.minusDays(ABANDON_AFTER), TODAY.plusDays(5)));

        var result = reconciler.reconcile(TODAY);

        verify(repo, never()).advanceStatus(anyLong(), any(), any());
        verify(repo).touchLastChecked(1);
        assertThat(result.transitionedIds()).isEmpty();
    }

    @Test
    void casGuardPreventsRegressionOnDuplicateReconcile() {
        // repo reports the guarded UPDATE moved nothing (row already advanced) -> not counted,
        // and the row is touched instead
        queue(row(1, IndexEventStatus.ANNOUNCED, TODAY.minusDays(10), TODAY));
        when(repo.advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE)).thenReturn(false);

        var result = reconciler.reconcile(TODAY);

        verify(repo).touchLastChecked(1);
        assertThat(result.transitionedIds()).isEmpty();
    }

    @Test
    void failSoftPerRowIsolatesADbFailure() {
        // row 1's transition throws; row 2 must still be reconciled and counted
        queue(row(1, IndexEventStatus.ANNOUNCED, TODAY.minusDays(10), TODAY),
              row(2, IndexEventStatus.ANNOUNCED, TODAY.minusDays(10), TODAY));
        when(repo.advanceStatus(1, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE))
                .thenThrow(new RuntimeException("db down"));
        when(repo.advanceStatus(2, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE)).thenReturn(true);

        var result = reconciler.reconcile(TODAY);

        verify(repo).advanceStatus(2, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE);
        assertThat(result.transitionedIds()).containsExactly(2L);
    }

    @Test
    void emptyQueueYieldsEmptyResult() {
        queue();

        var result = reconciler.reconcile(TODAY);

        assertThat(result.transitionedIds()).isEmpty();
        verify(repo, never()).advanceStatus(anyLong(), any(), any());
        verify(repo, never()).touchLastChecked(anyLong());
    }
}
