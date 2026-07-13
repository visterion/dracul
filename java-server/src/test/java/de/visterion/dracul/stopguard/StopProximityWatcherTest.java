package de.visterion.dracul.stopguard;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StopProximityWatcherTest {

    private static final String CONNECTION = "depot-1";

    private final HeldPositionService heldPositions = mock(HeldPositionService.class);
    private final AgoraMarketData marketData = mock(AgoraMarketData.class);
    private final StopAlertEmitter emitter = mock(StopAlertEmitter.class);
    private final StopProximityWatcher watcher =
            new StopProximityWatcher(heldPositions, marketData, emitter, 0.5, CONNECTION, "");

    /** A held depot position with full research context (verdict, kill criteria, stops). */
    private static HeldPosition withContext(String symbol, String verdictId, BigDecimal activeStop) {
        return new HeldPosition(symbol, new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("1000"), new BigDecimal("0"), verdictId, null, "6m", null,
                new BigDecimal("90"), activeStop, "reconcile", "2026-01-01T00:00:00Z");
    }

    /** A held depot position with no context row at all -- TA-only, everything context-shaped
     *  is null (mirrors {@code HeldPositionService.join}'s no-context branch). */
    private static HeldPosition noContext(String symbol) {
        return new HeldPosition(symbol, new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("1000"), new BigDecimal("0"), null, null, null, null, null, null,
                null, null);
    }

    @Test
    void positionWithContextStopAndPriceAtStopEmitsBreach() {
        var position = withContext("AAA", "11111111-1111-1111-1111-111111111111", new BigDecimal("100"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        // price 100 == stop 100 -> BREACHED regardless of the (unavailable) ATR band.
        when(marketData.quotes(anyCollection()))
                .thenReturn(Map.of("AAA", new Quote(new BigDecimal("100"), BigDecimal.ZERO)));

        watcher.poll();

        verify(marketData, times(1)).quotes(anyCollection());
        verify(emitter).emit(eq("default"), eq("11111111-1111-1111-1111-111111111111"), eq("AAA"),
                eq(StopZone.BREACHED), eq(new BigDecimal("100")),
                eq(new BigDecimal("100")), any(Instant.class));
    }

    @Test
    void nullContextPositionSkipsWithoutError() {
        var position = noContext("AAA");
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));

        watcher.poll();   // must not throw

        verifyNoInteractions(marketData);
        verifyNoInteractions(emitter);
    }

    @Test
    void priceFetchFailureSkipsTickWithoutThrowing() {
        var position = withContext("AAA", "11111111-1111-1111-1111-111111111111", new BigDecimal("100"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        when(marketData.quotes(anyCollection())).thenThrow(new RuntimeException("429"));

        watcher.poll();   // must not throw

        verifyNoInteractions(emitter);
    }

    @Test
    void missingQuoteForSymbolSkipsThatPosition() {
        var position = withContext("AAA", "11111111-1111-1111-1111-111111111111", new BigDecimal("100"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());   // no AAA quote

        watcher.poll();

        verifyNoInteractions(emitter);
    }

    @Test
    void depotUnavailableYieldsEmptyPositionsAndNoOp() {
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of());

        watcher.poll();

        verifyNoInteractions(marketData);
        verifyNoInteractions(emitter);
    }

    @Test
    void contextStopWithoutLinkedVerdictSkipsWithoutError() {
        // A context row can exist with a frozen stop but no linked verdict (TA-only backfill,
        // PositionReconciler's "source: none" path) -- there is nowhere to persist the alert.
        var position = withContext("AAA", null, new BigDecimal("100"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        when(marketData.quotes(anyCollection()))
                .thenReturn(Map.of("AAA", new Quote(new BigDecimal("100"), BigDecimal.ZERO)));

        watcher.poll();   // must not throw

        verifyNoInteractions(emitter);
    }
}
