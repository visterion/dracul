package de.visterion.dracul.watchlist;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class WatchlistPriceRefresherTest {

    @Test
    void updatesOnlyResolvedTickers() {
        var repo = mock(WatchlistRepository.class);
        var port = mock(AgoraMarketData.class);
        when(repo.distinctTickers()).thenReturn(List.of("AVGO", "NVDA"));
        when(port.quotes(List.of("AVGO", "NVDA")))
                .thenReturn(Map.of("AVGO", new Quote(new BigDecimal("382.07"), new BigDecimal("-0.9"))));

        new WatchlistPriceRefresher(repo, port).refresh();

        verify(repo).updatePriceByTicker("AVGO", 382.07, -0.9);
        verify(repo, never()).updatePriceByTicker(eq("NVDA"), anyDouble(), anyDouble());
    }

    @Test
    void noTickersDoesNothing() {
        var repo = mock(WatchlistRepository.class);
        var port = mock(AgoraMarketData.class);
        when(repo.distinctTickers()).thenReturn(List.of());

        new WatchlistPriceRefresher(repo, port).refresh();

        verify(port, never()).quotes(anyCollection());
        verify(repo, never()).updatePriceByTicker(anyString(), anyDouble(), anyDouble());
    }

    /** Task 8, Step 11: pins the design rule that a batch degrades per-symbol, never as a whole
     *  — one unresolvable ticker (e.g. Agora's noData shape for an unknown symbol) must not stop
     *  the other rows of the SAME batch call from being refreshed. */
    @Test
    void oneUnresolvedTickerInABatchStillUpdatesTheOthers() {
        var repo = mock(WatchlistRepository.class);
        var port = mock(AgoraMarketData.class);
        when(repo.distinctTickers()).thenReturn(List.of("AVGO", "NOKIA", "NVDA"));
        when(port.quotes(List.of("AVGO", "NOKIA", "NVDA"))).thenReturn(Map.of(
                "AVGO", new Quote(new BigDecimal("382.07"), new BigDecimal("-0.9")),
                "NVDA", new Quote(new BigDecimal("143.20"), new BigDecimal("1.1"))));

        new WatchlistPriceRefresher(repo, port).refresh();

        verify(repo).updatePriceByTicker("AVGO", 382.07, -0.9);
        verify(repo).updatePriceByTicker("NVDA", 143.20, 1.1);
        verify(repo, never()).updatePriceByTicker(eq("NOKIA"), anyDouble(), anyDouble());
        verify(port, times(1)).quotes(anyCollection());
    }

    @Test
    void providerExceptionIsSwallowed() {
        var repo = mock(WatchlistRepository.class);
        var port = mock(AgoraMarketData.class);
        when(repo.distinctTickers()).thenReturn(List.of("AVGO"));
        when(port.quotes(anyCollection())).thenThrow(new RuntimeException("provider down"));

        new WatchlistPriceRefresher(repo, port).refresh(); // must not throw

        verify(repo, never()).updatePriceByTicker(anyString(), anyDouble(), anyDouble());
    }
}
