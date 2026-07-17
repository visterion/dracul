package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
import de.visterion.dracul.daywalker.detect.TriggerType;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraIntraday;
import de.visterion.dracul.hunting.agora.IntradayCandles;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerEventEngineTest {

    private static HeldPosition position(String symbol, double avgPrice) {
        return new HeldPosition(symbol, BigDecimal.valueOf(10), BigDecimal.valueOf(avgPrice),
                BigDecimal.valueOf(avgPrice * 10), BigDecimal.ZERO,
                null, null, null, null, null, null, null, null);
    }

    private static HeldPosition heldPositionWithContext(String symbol, double avgPrice, BigDecimal activeStop) {
        return new HeldPosition(symbol, BigDecimal.valueOf(10), BigDecimal.valueOf(avgPrice),
                BigDecimal.valueOf(avgPrice * 10), BigDecimal.ZERO,
                "verdict-1", null, "swing", null, activeStop, activeStop, "executor", "2026-06-01T00:00:00Z");
    }

    private static List<BigDecimal> closes(double... v) {
        return java.util.Arrays.stream(v).mapToObj(BigDecimal::valueOf).toList();
    }

    private DaywalkerEventEngine engine(HeldPositionService hp, de.visterion.dracul.watchlist.WatchlistRepository wl,
                                        AgoraIntraday in, AgoraCompanyData cd, AgoraFilings fi,
                                        DaywalkerAlertRepository al) {
        return new DaywalkerEventEngine(hp, wl, in, cd, fi, al, 0.03, 3.0, 3600, "depot-1");
    }

    @Test
    void emitsPriceSpikeAndAppliesCooldown() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var now = Instant.parse("2026-06-03T18:00:00Z");
        List<TriggerEvent> events = engine(hp, wl, in, cd, fi, al).detect(null, now);

        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);

        when(al.lastAlertAtAnyOwner("ACME", "PRICE_SPIKE"))
                .thenReturn(Optional.of(now.minusSeconds(60)));
        assertThat(engine(hp, wl, in, cd, fi, al).detect(null, now)).isEmpty();
    }

    @Test
    void dedupsSameSymbolAndFetchesMarketDataOnce() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        // A depot never actually returns two rows for the same symbol, but the engine must
        // still dedup defensively and fetch market data only once per distinct symbol.
        when(hp.openPositions("depot-1")).thenReturn(List.of(
                position("AAPL", 100), position("AAPL", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("AAPL")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("AAPL"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("AAPL")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);
        verify(in, times(1)).candles("AAPL");
    }

    @Test
    void filingsFailureDegradesGracefully() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenThrow(new RuntimeException("boom"));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));
        assertThat(events).isEmpty();
    }

    @Test
    void emptyDepotReturnsNoEvents() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        when(hp.openPositions("depot-1")).thenReturn(List.of());
        when(wl.distinctSweepRows()).thenReturn(List.of());
        var engine = engine(hp, wl, mock(AgoraIntraday.class), mock(AgoraCompanyData.class),
                mock(AgoraFilings.class), mock(DaywalkerAlertRepository.class));
        assertThat(engine.detect(null, Instant.now())).isEmpty();
    }

    @Test
    void positionWithContextGetsEnrichedEventWithLevelsAndBreach() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(
                List.of(heldPositionWithContext("ACME", 100, new BigDecimal("96"))));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 95), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        var ev = events.get(0);
        assertThat(ev.positionId()).isEqualTo("ACME");
        assertThat(ev.position()).isNotNull();
        assertThat(ev.position().activeStop()).isEqualByComparingTo("96");
        assertThat(ev.breachedLevel()).isEqualTo("STOP");
    }

    @Test
    void nullContextPositionStillEmitsEventUnenriched() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        var ev = events.get(0);
        assertThat(ev.positionId()).isEqualTo("ACME");
        assertThat(ev.position()).isNotNull();
        assertThat(ev.position().activeStop()).isNull();
        assertThat(ev.breachedLevel()).isNull();
    }

    @Test
    void multiplePositionsEmitPerSymbolEventsWithIndependentCooldown() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(
                position("ACME", 100), position("AAPL", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(in.candles("AAPL")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(anyString(), any(), any())).thenReturn(List.of());
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var now = Instant.parse("2026-06-03T18:00:00Z");
        var events = engine(hp, wl, in, cd, fi, al).detect(null, now);
        assertThat(events).extracting(TriggerEvent::positionId).containsExactlyInAnyOrder("ACME", "AAPL");

        // Independent cooldown: ACME recently alerted, AAPL free -> only AAPL emitted.
        when(al.lastAlertAtAnyOwner("ACME", "PRICE_SPIKE")).thenReturn(Optional.of(now.minusSeconds(60)));
        var events2 = engine(hp, wl, in, cd, fi, al).detect(null, now);
        assertThat(events2).extracting(TriggerEvent::positionId).containsExactly("AAPL");
    }

    @Test
    void emptyDepotWithNonEmptyWatchlistStillSweeps() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of());
        when(wl.distinctSweepRows()).thenReturn(List.of(
                new de.visterion.dracul.watchlist.WatchlistRepository.SweepRow("WTCH", "Watch Co", 50.0)));
        when(in.candles("WTCH")).thenReturn(new IntradayCandles(closes(50, 55), List.of()));
        when(cd.news(eq("WTCH"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("WTCH")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).extracting(TriggerEvent::triggerType).containsExactly(TriggerType.PRICE_SPIKE);
        assertThat(events.get(0).positionId()).isNull();
        assertThat(events.get(0).currentPrice()).isNotNull();
    }

    @Test
    void depotRepresentativeWinsOverWatchlistRowForSameSymbol() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of(
                new de.visterion.dracul.watchlist.WatchlistRepository.SweepRow("ACME", "Acme Corp", 99.0)));
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        // Exactly one sweep of the symbol, and the depot representative carries positionId.
        verify(in, times(1)).candles("ACME");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).positionId()).isEqualTo("ACME");
    }

    @Test
    void emptyUnionMakesNoAgoraCallsAndReturnsNoEvents() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of());
        when(wl.distinctSweepRows()).thenReturn(List.of());

        assertThat(engine(hp, wl, in, cd, fi, al).detect(null, Instant.now())).isEmpty();
        verifyNoInteractions(in, cd, fi);
    }

    @Test
    void alertPersistedUnderNonPrimaryOwnerSuppressesReEmission() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of());
        when(wl.distinctSweepRows()).thenReturn(List.of(
                new de.visterion.dracul.watchlist.WatchlistRepository.SweepRow("WTCH", "Watch Co", 50.0)));
        when(in.candles("WTCH")).thenReturn(new IntradayCandles(closes(50, 55), List.of()));
        when(cd.news(eq("WTCH"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("WTCH")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));

        var now = Instant.parse("2026-06-03T18:00:00Z");
        // R2: the alert row was written under a NON-primary watcher's user_id; the engine's
        // owner-agnostic lookup must still see it and suppress the re-emission.
        when(al.lastAlertAtAnyOwner("WTCH", "PRICE_SPIKE")).thenReturn(Optional.of(now.minusSeconds(60)));
        when(al.lastAlertAtAnyOwner(eq("WTCH"), argThat(t -> !"PRICE_SPIKE".equals(t))))
                .thenReturn(Optional.empty());

        assertThat(engine(hp, wl, in, cd, fi, al).detect(null, now)).isEmpty();
    }

    @Test
    void stopProximityAlertDoesNotSuppressPriceSpike_triggerTypeDisjointness() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));

        var now = Instant.parse("2026-06-03T18:00:00Z");
        // A STOP_PROXIMITY row (StopAlertEmitter vocabulary) for the same symbol must NOT
        // suppress a PRICE_SPIKE engine event — the (symbol, trigger_type) key is disjoint.
        when(al.lastAlertAtAnyOwner("ACME", "STOP_PROXIMITY")).thenReturn(Optional.of(now.minusSeconds(60)));
        when(al.lastAlertAtAnyOwner("ACME", "PRICE_SPIKE")).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, now);

        assertThat(events).extracting(TriggerEvent::triggerType).containsExactly(TriggerType.PRICE_SPIKE);
        verify(al).lastAlertAtAnyOwner("ACME", "PRICE_SPIKE");
    }
}
