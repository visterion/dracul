package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
import de.visterion.dracul.daywalker.detect.TriggerType;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraIntraday;
import de.visterion.dracul.hunting.agora.IntradayCandles;
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

    private static WatchlistItem heldItem(String id, String ticker, String owner, double entry) {
        return new WatchlistItem(id, ticker, ticker + " Corp", 100.0, 0.0,
                "calm", "2026-06-01", "HELD", null, List.of(), List.of(), entry, 30.0, owner, null, null);
    }

    private static List<BigDecimal> closes(double... v) {
        return java.util.Arrays.stream(v).mapToObj(BigDecimal::valueOf).toList();
    }

    private DaywalkerEventEngine engine(WatchlistRepository wl, AgoraIntraday in,
                                        AgoraCompanyData cd, AgoraFilings fi,
                                        DaywalkerAlertRepository al) {
        return new DaywalkerEventEngine(wl, in, cd, fi, al, 0.03, 3.0, 3600);
    }

    @Test
    void emitsPriceSpikeAndAppliesCooldown() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(item("id-1", "ACME", "u1@x.com")));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var now = Instant.parse("2026-06-03T18:00:00Z");
        List<TriggerEvent> events = engine(wl, in, cd, fi, al).detect(null, now);

        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);

        when(al.lastAlertAt("u1@x.com", "ACME", "PRICE_SPIKE"))
                .thenReturn(Optional.of(now.minusSeconds(60)));
        assertThat(engine(wl, in, cd, fi, al).detect(null, now)).isEmpty();
    }

    @Test
    void dedupsSameTickerAcrossUsersAndFetchesMarketDataOnce() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(
                item("id-a", "AAPL", "u1@x.com"),
                item("id-b", "AAPL", "u2@x.com")));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of());
        when(in.candles("AAPL")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("AAPL"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("AAPL")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);
        verify(in, times(1)).candles("AAPL");
    }

    @Test
    void emitsWhenAnyOwnerFreeDropsWhenAllInCooldown() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(
                item("id-a", "AAPL", "u1@x.com"),
                item("id-b", "AAPL", "u2@x.com")));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of());
        when(in.candles("AAPL")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("AAPL"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("AAPL")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));

        var now = Instant.parse("2026-06-03T18:00:00Z");
        when(al.lastAlertAt("u1@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.of(now.minusSeconds(60)));
        when(al.lastAlertAt("u2@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.empty());
        assertThat(engine(wl, in, cd, fi, al).detect(null, now)).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);

        when(al.lastAlertAt("u2@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.of(now.minusSeconds(60)));
        assertThat(engine(wl, in, cd, fi, al).detect(null, now)).isEmpty();
    }

    @Test
    void filingsFailureDegradesGracefully() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(item("id-1", "ACME", "u1@x.com")));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenThrow(new RuntimeException("boom"));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));
        assertThat(events).isEmpty();
    }

    @Test
    void emptyWatchlistReturnsNoEvents() {
        var wl = mock(WatchlistRepository.class);
        when(wl.findAll()).thenReturn(List.of());
        var engine = engine(wl, mock(AgoraIntraday.class), mock(AgoraCompanyData.class),
                mock(AgoraFilings.class), mock(DaywalkerAlertRepository.class));
        assertThat(engine.detect(null, Instant.now())).isEmpty();
    }

    @Test
    void heldOwnerGetsPerPositionEventWithLevelsAndBreach() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(heldItem("id-h", "ACME", "u1@x.com", 100)));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of("id-h",
                new de.visterion.dracul.watchlist.PositionRisk("id-h", "2026-06-01",
                        new BigDecimal("96"), new BigDecimal("96"), new BigDecimal("120"),
                        new BigDecimal("95"), new BigDecimal("2"))));
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 95), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        var ev = events.get(0);
        assertThat(ev.positionId()).isEqualTo("id-h");
        assertThat(ev.position()).isNotNull();
        assertThat(ev.position().activeStop()).isEqualByComparingTo("96");
        assertThat(ev.breachedLevel()).isEqualTo("STOP");
    }

    @Test
    void mixedHeldAndWatchOnlySameTickerEmitsBoth() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(
                heldItem("id-h", "ACME", "u1@x.com", 100),
                item("id-w", "ACME", "u2@x.com")));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of("id-h",
                new de.visterion.dracul.watchlist.PositionRisk("id-h", "2026-06-01",
                        new BigDecimal("96"), new BigDecimal("96"), new BigDecimal("120"),
                        new BigDecimal("95"), new BigDecimal("2"))));
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 95), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(2);
        assertThat(events).extracting(TriggerEvent::positionId).containsExactlyInAnyOrder("id-h", null);
    }

    @Test
    void multiOwnerHeldEmitsPerOwnerEvents() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(
                heldItem("id-1", "ACME", "u1@x.com", 100),
                heldItem("id-2", "ACME", "u2@x.com", 100)));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of(
                "id-1", new de.visterion.dracul.watchlist.PositionRisk("id-1", "2026-06-01",
                        new BigDecimal("96"), new BigDecimal("96"), new BigDecimal("120"),
                        new BigDecimal("95"), new BigDecimal("2")),
                "id-2", new de.visterion.dracul.watchlist.PositionRisk("id-2", "2026-06-01",
                        new BigDecimal("96"), new BigDecimal("96"), new BigDecimal("120"),
                        new BigDecimal("95"), new BigDecimal("2"))));
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 95), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var now = Instant.parse("2026-06-03T18:00:00Z");
        var events = engine(wl, in, cd, fi, al).detect(null, now);
        assertThat(events).hasSize(2);
        assertThat(events).extracting(TriggerEvent::positionId).containsExactlyInAnyOrder("id-1", "id-2");

        // Independent cooldown: u1 recently alerted, u2 free -> only id-2 emitted.
        when(al.lastAlertAt("u1@x.com", "ACME", "PRICE_SPIKE")).thenReturn(Optional.of(now.minusSeconds(60)));
        var events2 = engine(wl, in, cd, fi, al).detect(null, now);
        assertThat(events2).extracting(TriggerEvent::positionId).containsExactly("id-2");
    }

    @Test
    void watchOnlyItemStaysGeneric() {
        var wl = mock(WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(wl.findAll()).thenReturn(List.of(item("id-w", "ACME", "u1@x.com")));
        when(wl.positionRiskByItemId()).thenReturn(java.util.Map.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).positionId()).isNull();
        assertThat(events.get(0).position()).isNull();
    }
}
