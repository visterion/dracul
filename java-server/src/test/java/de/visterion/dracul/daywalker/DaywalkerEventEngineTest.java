package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
import de.visterion.dracul.daywalker.detect.TriggerType;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.edgar.EdgarFormFourAdapter;
import de.visterion.dracul.hunting.finnhub.FinnhubNewsAdapter;
import de.visterion.dracul.hunting.yahoo.IntradayCandles;
import de.visterion.dracul.hunting.yahoo.YahooIntradayAdapter;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerEventEngineTest {

    private static WatchlistItem item(String id, String ticker, String owner) {
        return new WatchlistItem(id, ticker, ticker + " Corp", 100.0, 0.0,
                "calm", "2026-06-01", "", null, List.of(), List.of(), null, null, owner, null, null);
    }

    private static List<BigDecimal> closes(double... v) {
        return java.util.Arrays.stream(v).mapToObj(BigDecimal::valueOf).toList();
    }

    private DaywalkerEventEngine engine(WatchlistRepository wl, YahooIntradayAdapter ya,
                                        FinnhubNewsAdapter fh, EdgarFormFourAdapter ed,
                                        DaywalkerAlertRepository al) {
        return new DaywalkerEventEngine(wl, ya, fh, ed, al, 0.03, 3.0, 3600);
    }

    @Test
    void emitsPriceSpikeAndAppliesCooldown() {
        var wl = mock(WatchlistRepository.class);
        var ya = mock(YahooIntradayAdapter.class);
        var fh = mock(FinnhubNewsAdapter.class);
        var ed = mock(EdgarFormFourAdapter.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(item("id-1", "ACME", "u1@x.com")));
        when(ya.intradayCandles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(fh.companyNews(eq("ACME"), any(), any())).thenReturn(List.of());
        when(fh.recommendationTrend("ACME")).thenReturn(List.of());
        when(ed.recentFilings(any(), any())).thenReturn(DataSourceResult.healthy("edgar", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var now = Instant.parse("2026-06-03T18:00:00Z");
        List<TriggerEvent> events = engine(wl, ya, fh, ed, al).detect(null, now);

        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);

        when(al.lastAlertAt("u1@x.com", "ACME", "PRICE_SPIKE"))
                .thenReturn(Optional.of(now.minusSeconds(60)));
        assertThat(engine(wl, ya, fh, ed, al).detect(null, now)).isEmpty();
    }

    @Test
    void dedupsSameTickerAcrossUsersAndFetchesMarketDataOnce() {
        var wl = mock(WatchlistRepository.class);
        var ya = mock(YahooIntradayAdapter.class);
        var fh = mock(FinnhubNewsAdapter.class);
        var ed = mock(EdgarFormFourAdapter.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(
                item("id-a", "AAPL", "u1@x.com"),
                item("id-b", "AAPL", "u2@x.com")));
        when(ya.intradayCandles("AAPL")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(fh.companyNews(eq("AAPL"), any(), any())).thenReturn(List.of());
        when(fh.recommendationTrend("AAPL")).thenReturn(List.of());
        when(ed.recentFilings(any(), any())).thenReturn(DataSourceResult.healthy("edgar", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(wl, ya, fh, ed, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);
        verify(ya, times(1)).intradayCandles("AAPL");
    }

    @Test
    void emitsWhenAnyOwnerFreeDropsWhenAllInCooldown() {
        var wl = mock(WatchlistRepository.class);
        var ya = mock(YahooIntradayAdapter.class);
        var fh = mock(FinnhubNewsAdapter.class);
        var ed = mock(EdgarFormFourAdapter.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(
                item("id-a", "AAPL", "u1@x.com"),
                item("id-b", "AAPL", "u2@x.com")));
        when(ya.intradayCandles("AAPL")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(fh.companyNews(eq("AAPL"), any(), any())).thenReturn(List.of());
        when(fh.recommendationTrend("AAPL")).thenReturn(List.of());
        when(ed.recentFilings(any(), any())).thenReturn(DataSourceResult.healthy("edgar", List.of()));

        var now = Instant.parse("2026-06-03T18:00:00Z");
        when(al.lastAlertAt("u1@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.of(now.minusSeconds(60)));
        when(al.lastAlertAt("u2@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.empty());
        assertThat(engine(wl, ya, fh, ed, al).detect(null, now)).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);

        when(al.lastAlertAt("u2@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.of(now.minusSeconds(60)));
        assertThat(engine(wl, ya, fh, ed, al).detect(null, now)).isEmpty();
    }

    @Test
    void edgarFailureDegradesGracefully() {
        var wl = mock(WatchlistRepository.class);
        var ya = mock(YahooIntradayAdapter.class);
        var fh = mock(FinnhubNewsAdapter.class);
        var ed = mock(EdgarFormFourAdapter.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(item("id-1", "ACME", "u1@x.com")));
        when(ya.intradayCandles("ACME")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(fh.companyNews(eq("ACME"), any(), any())).thenReturn(List.of());
        when(fh.recommendationTrend("ACME")).thenReturn(List.of());
        when(ed.recentFilings(any(), any())).thenThrow(new RuntimeException("EDGAR down"));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(wl, ya, fh, ed, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));
        assertThat(events).isEmpty();
    }

    @Test
    void emptyWatchlistReturnsNoEvents() {
        var wl = mock(WatchlistRepository.class);
        when(wl.findAll()).thenReturn(List.of());
        var engine = engine(wl, mock(YahooIntradayAdapter.class), mock(FinnhubNewsAdapter.class),
                mock(EdgarFormFourAdapter.class), mock(DaywalkerAlertRepository.class));
        assertThat(engine.detect(null, Instant.now())).isEmpty();
    }
}
