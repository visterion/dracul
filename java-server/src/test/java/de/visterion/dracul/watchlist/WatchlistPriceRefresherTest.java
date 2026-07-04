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
