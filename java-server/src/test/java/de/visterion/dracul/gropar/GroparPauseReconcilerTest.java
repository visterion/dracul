package de.visterion.dracul.gropar;

import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class GroparPauseReconcilerTest {

    private WatchlistRepository repo;
    private VistierieClient vistierie;
    private GroparPauseReconciler reconciler;

    @BeforeEach
    void setUp() {
        repo = mock(WatchlistRepository.class);
        vistierie = mock(VistierieClient.class);
        reconciler = new GroparPauseReconciler(repo, vistierie);
    }

    @Test
    void pausesWhenNoHeldPositions() {
        when(repo.countHeldAll()).thenReturn(0L);
        reconciler.reconcile();
        verify(vistierie).patchAgent("gropar", true);
    }

    @Test
    void unpausesWhenHeldPositionsExist() {
        when(repo.countHeldAll()).thenReturn(2L);
        reconciler.reconcile();
        verify(vistierie).patchAgent("gropar", false);
    }

    @Test
    void suppressesRedundantPatchWhenStateUnchanged() {
        when(repo.countHeldAll()).thenReturn(0L);
        reconciler.reconcile();
        reconciler.reconcile();
        verify(vistierie, times(1)).patchAgent("gropar", true);
    }

    @Test
    void retriesAfterPatchFailure() {
        when(repo.countHeldAll()).thenReturn(0L);
        doThrow(new RuntimeException("vistierie down"))
                .doNothing()
                .when(vistierie).patchAgent("gropar", true);

        reconciler.reconcile(); // throws internally, caught+logged, lastApplied NOT advanced
        reconciler.reconcile(); // retries the patch

        verify(vistierie, times(2)).patchAgent("gropar", true);
    }
}
