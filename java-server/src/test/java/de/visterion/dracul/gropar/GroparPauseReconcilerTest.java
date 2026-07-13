package de.visterion.dracul.gropar;

import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

class GroparPauseReconcilerTest {

    private static final String CONNECTION = "depot-1";

    private HeldPositionService heldPositionService;
    private VistierieClient vistierie;
    private GroparPauseReconciler reconciler;

    @BeforeEach
    void setUp() {
        heldPositionService = mock(HeldPositionService.class);
        vistierie = mock(VistierieClient.class);
        reconciler = new GroparPauseReconciler(heldPositionService, vistierie, CONNECTION);
    }

    private HeldPosition position(String symbol) {
        return new HeldPosition(symbol, new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("1000"), new BigDecimal("0"),
                null, null, null, null, null, null, null);
    }

    @Test
    void pausesWhenNoOpenDepotPositions() {
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());
        reconciler.reconcile();
        verify(vistierie).patchAgent("gropar", true);
    }

    @Test
    void unpausesWhenOpenDepotPositionsExist() {
        when(heldPositionService.openPositions(CONNECTION))
                .thenReturn(List.of(position("AAA"), position("BBB")));
        reconciler.reconcile();
        verify(vistierie).patchAgent("gropar", false);
    }

    @Test
    void suppressesRedundantPatchWhenStateUnchanged() {
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());
        reconciler.reconcile();
        reconciler.reconcile();
        verify(vistierie, times(1)).patchAgent("gropar", true);
    }

    @Test
    void retriesAfterPatchFailure() {
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());
        doThrow(new RuntimeException("vistierie down"))
                .doNothing()
                .when(vistierie).patchAgent("gropar", true);

        reconciler.reconcile(); // throws internally, caught+logged, lastApplied NOT advanced
        reconciler.reconcile(); // retries the patch

        verify(vistierie, times(2)).patchAgent("gropar", true);
    }
}
