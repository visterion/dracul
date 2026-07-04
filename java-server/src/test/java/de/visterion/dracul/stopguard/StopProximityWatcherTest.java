package de.visterion.dracul.stopguard;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.watchlist.PositionRisk;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StopProximityWatcherTest {

    private final WatchlistRepository watchlist = mock(WatchlistRepository.class);
    private final AgoraMarketData marketData = mock(AgoraMarketData.class);
    private final StopAlertEmitter emitter = mock(StopAlertEmitter.class);
    private final StopProximityWatcher watcher =
            new StopProximityWatcher(watchlist, marketData, emitter, 0.5);

    /** Back-compat 16-arg WatchlistItem constructor. Only id, ticker, tag="HELD",
     *  entryPrice=100.0, shareCount=10.0, owner matter to the watcher. */
    private static WatchlistItem held(String id, String ticker, String owner) {
        return new WatchlistItem(
                id,          // id
                ticker,      // ticker
                "Test Co",   // companyName
                105.0,       // currentPrice
                0.0,         // dayChangePercent
                "calm",      // status
                "2026-01-01",// addedAt
                "HELD",      // tag
                null,        // verdictId
                List.of(),   // alerts
                List.of(),   // priceHistory30d
                100.0,       // entryPrice
                10.0,        // shareCount
                owner,       // owner
                "USD",       // currency
                "USD"        // entryCurrency
        );
    }

    @Test
    void evaluatesEachHeldPositionAndEmitsProximity() {
        var item = held("11111111-1111-1111-1111-111111111111", "AAA", "alice");
        when(watchlist.findAll()).thenReturn(List.of(item));
        when(watchlist.positionRiskByItemId()).thenReturn(Map.of(item.id(),
                new PositionRisk(item.id(), "2026-01-01", new BigDecimal("90"),
                        new BigDecimal("100"), new BigDecimal("130"),
                        new BigDecimal("105"), new BigDecimal("10"))));
        // price 103, stop 100, atr 10, mult 0.5 -> band 105 -> PROXIMITY
        when(marketData.quotes(anyCollection()))
                .thenReturn(Map.of("AAA", new Quote(new BigDecimal("103"), BigDecimal.ZERO)));

        watcher.poll();

        verify(marketData, times(1)).quotes(anyCollection());
        verify(emitter).emit(eq("alice"), eq(item.id()), eq("AAA"),
                eq(StopZone.PROXIMITY), eq(new BigDecimal("103")),
                eq(new BigDecimal("100")), any(Instant.class));
    }

    @Test
    void skipsPositionsWithoutSnapshot() {
        var item = held("11111111-1111-1111-1111-111111111111", "AAA", "alice");
        when(watchlist.findAll()).thenReturn(List.of(item));
        when(watchlist.positionRiskByItemId()).thenReturn(Map.of());   // no snapshot
        watcher.poll();
        verifyNoInteractions(marketData);
        verifyNoInteractions(emitter);
    }

    @Test
    void priceFetchFailureSkipsTickWithoutThrowing() {
        var item = held("11111111-1111-1111-1111-111111111111", "AAA", "alice");
        when(watchlist.findAll()).thenReturn(List.of(item));
        when(watchlist.positionRiskByItemId()).thenReturn(Map.of(item.id(),
                new PositionRisk(item.id(), "2026-01-01", null,
                        new BigDecimal("100"), null, null, new BigDecimal("10"))));
        when(marketData.quotes(anyCollection())).thenThrow(new RuntimeException("429"));
        watcher.poll();   // must not throw
        verifyNoInteractions(emitter);
    }

    @Test
    void missingQuoteForTickerSkipsThatPosition() {
        var item = held("11111111-1111-1111-1111-111111111111", "AAA", "alice");
        when(watchlist.findAll()).thenReturn(List.of(item));
        when(watchlist.positionRiskByItemId()).thenReturn(Map.of(item.id(),
                new PositionRisk(item.id(), "2026-01-01", null,
                        new BigDecimal("100"), null, null, new BigDecimal("10"))));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());   // no AAA quote
        watcher.poll();
        verifyNoInteractions(emitter);
    }
}
