package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
import de.visterion.dracul.daywalker.detect.TriggerType;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraIntraday;
import de.visterion.dracul.hunting.agora.IntradayCandles;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.hunting.agora.SectorResolver;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.position.PortfolioWeights;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerEventEngineTest {

    private static HeldPosition position(String symbol, double avgPrice) {
        return new HeldPosition(symbol, BigDecimal.valueOf(10), BigDecimal.valueOf(avgPrice),
                BigDecimal.valueOf(avgPrice * 10), BigDecimal.ZERO, "USD",
                null, null, null, null, null, null, null, null);
    }

    private static HeldPosition heldPositionWithContext(String symbol, double avgPrice, BigDecimal activeStop) {
        return new HeldPosition(symbol, BigDecimal.valueOf(10), BigDecimal.valueOf(avgPrice),
                BigDecimal.valueOf(avgPrice * 10), BigDecimal.ZERO, "USD",
                "verdict-1", null, "swing", null, activeStop, activeStop, "executor", "2026-06-01T00:00:00Z");
    }

    private static HeldPosition shortPositionWithStop(String symbol, double avgPrice, BigDecimal activeStop) {
        return new HeldPosition(symbol, BigDecimal.valueOf(-10), BigDecimal.valueOf(avgPrice),
                BigDecimal.valueOf(avgPrice * 10), BigDecimal.ZERO, "USD",
                "verdict-1", null, "swing", null, activeStop, activeStop, "executor", "2026-06-01T00:00:00Z");
    }

    private static List<BigDecimal> closes(double... v) {
        return java.util.Arrays.stream(v).mapToObj(BigDecimal::valueOf).toList();
    }

    private static NewsHeadline macroHeadline(String text, Instant at) {
        return new NewsHeadline(text, "", "Reuters", "news", at, "http://n/m");
    }

    private static NewsHeadline macroHeadline(String text, Instant at, double credibility) {
        return new NewsHeadline(text, "", "wire", "news", at, "https://x/1", null, credibility);
    }

    private final PortfolioWeights portfolioWeights = mock(PortfolioWeights.class);
    private final SectorResolver sectors = mock(SectorResolver.class);

    private DaywalkerEventEngine engine(HeldPositionService hp, de.visterion.dracul.watchlist.WatchlistRepository wl,
                                        AgoraIntraday in, AgoraCompanyData cd, AgoraFilings fi,
                                        DaywalkerAlertRepository al) {
        return engine(hp, wl, in, cd, fi, al, 60_000);
    }

    private DaywalkerEventEngine engine(HeldPositionService hp, de.visterion.dracul.watchlist.WatchlistRepository wl,
                                        AgoraIntraday in, AgoraCompanyData cd, AgoraFilings fi,
                                        DaywalkerAlertRepository al, long budgetMs) {
        return new DaywalkerEventEngine(hp, wl, in, cd, fi, al, portfolioWeights, sectors,
                0.03, 3.0, 3600, 28800, budgetMs, "depot-1", "true");
    }

    private DaywalkerEventEngine engine(HeldPositionService hp, de.visterion.dracul.watchlist.WatchlistRepository wl,
                                        AgoraIntraday in, AgoraCompanyData cd, AgoraFilings fi,
                                        DaywalkerAlertRepository al, long budgetMs, String watchlistScope) {
        return new DaywalkerEventEngine(hp, wl, in, cd, fi, al, portfolioWeights, sectors,
                0.03, 3.0, 3600, 28800, budgetMs, "depot-1", watchlistScope);
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

    @Test
    void budgetExhaustionSkipsUnfinishedSymbolsAndReturnsFinishedOnes() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("FAST", 100), position("SLOW", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("FAST")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(in.candles("SLOW")).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return new IntradayCandles(closes(100, 105), List.of());
        });
        when(cd.news(anyString(), any(), any())).thenReturn(List.of());
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al, 500).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        // SLOW is skipped for this poll (WARN with count); FAST's event survives.
        assertThat(events).extracting(TriggerEvent::symbol).containsExactly("FAST");
    }

    @Test
    void concurrencySmoke_manySymbolsAllProcessedWithinBudget() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        var positions = new java.util.ArrayList<HeldPosition>();
        for (int i = 0; i < 20; i++) positions.add(position("SYM" + i, 100));
        when(hp.openPositions("depot-1")).thenReturn(positions);
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles(anyString())).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(anyString(), any(), any())).thenReturn(List.of());
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(20);
    }

    @Test
    void shortPositionGetsSignCorrectGainLossAndDirectionAwareBreach() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        // short 10 @ 100, stop ABOVE at 110; close rallies to 112 -> price spike + stop breach
        when(hp.openPositions("depot-1")).thenReturn(
                List.of(shortPositionWithStop("ACME", 100, new BigDecimal("110"))));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 112), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        var ev = events.get(0);
        assertThat(ev.position().direction()).isEqualTo("short");
        // short P&L: (100 - 112)/100 * 100 = -12
        assertThat(ev.position().gainLossPct()).isEqualByComparingTo("-12");
        // short stop sits ABOVE: close 112 >= 110 -> STOP
        assertThat(ev.breachedLevel()).isEqualTo("STOP");
    }

    @Test
    void longPositionKeepsDirectionLongAndLongMath() {
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
        assertThat(events.get(0).position().direction()).isEqualTo("long");
        assertThat(events.get(0).position().gainLossPct()).isEqualByComparingTo("-5");
        assertThat(events.get(0).breachedLevel()).isEqualTo("STOP");
    }

    @Test
    void weightMapIsComputedFromTheFullPositionsListAndReachesThePositionContext() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        // TWO lots of ACME: putIfAbsent-style rep selection would only see the first lot;
        // the weight map must be computed from the FULL list (spec §9 Engine wiring).
        List<HeldPosition> full = List.of(position("ACME", 100), position("ACME", 100));
        when(hp.openPositions("depot-1")).thenReturn(full);
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(portfolioWeights.weightsBySymbol(full))
                .thenReturn(java.util.Map.of("ACME", new BigDecimal("100.0")));

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).position().weightPct()).isEqualByComparingTo("100.0");
        verify(portfolioWeights).weightsBySymbol(full);
    }

    @Test
    void heldEventCarriesSectorInsideThePositionBlockOnly() {
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
        when(sectors.sector("ACME")).thenReturn("Semiconductors");

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).position().sector()).isEqualTo("Semiconductors");
        assertThat(events.get(0).detail()).doesNotContainKey("sector"); // not duplicated (round 2, m-3)
    }

    @Test
    void watchlistOnlyEventCarriesSectorInTheDetailMap() {
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
        when(sectors.sector("WTCH")).thenReturn("Utilities");

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).position()).isNull();
        assertThat(events.get(0).detail()).containsEntry("sector", "Utilities");
    }

    @Test
    void macroOnlyHeadlineEmitsOneMacroPortfolioTriggerWithSnapshot() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of(
                new de.visterion.dracul.watchlist.WatchlistRepository.SweepRow("WTCH", "Watch Co", 50.0)));
        when(in.candles(anyString())).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"))));
        when(cd.news(eq("WTCH"), any(), any())).thenReturn(List.of());
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(java.util.Map.of("ACME", new BigDecimal("100.0")));
        when(sectors.cachedSector("ACME")).thenReturn("Semiconductors");

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        // NO per-symbol NEGATIVE_NEWS; exactly ONE MACRO_PORTFOLIO
        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.MACRO_PORTFOLIO);
        var ev = events.get(0);
        assertThat(ev.symbol()).isEqualTo(TriggerEvent.PORTFOLIO_SYMBOL);
        assertThat(ev.companyName()).isEqualTo("Portfolio");
        // snapshot: held symbols only, watchlist-only WTCH excluded
        assertThat(ev.portfolioSnapshot()).hasSize(1);
        var entry = ev.portfolioSnapshot().get(0);
        assertThat(entry).containsEntry("symbol", "ACME")
                .containsEntry("direction", "long")
                .containsEntry("sector", "Semiconductors");
        assertThat((BigDecimal) entry.get("weight_pct")).isEqualByComparingTo("100.0");
        // gain_loss_pct from |marketValue|/|quantity| vs avgPrice: 1000/10 = 100 vs 100 -> 0
        assertThat((BigDecimal) entry.get("gain_loss_pct")).isEqualByComparingTo("0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void macroWireHeadlineMapsCarryCredibility() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of(
                new de.visterion.dracul.watchlist.WatchlistRepository.SweepRow("WTCH", "Watch Co", 50.0)));
        when(in.candles(anyString())).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"), 0.9)));
        when(cd.news(eq("WTCH"), any(), any())).thenReturn(List.of());
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(java.util.Map.of("ACME", new BigDecimal("100.0")));
        when(sectors.cachedSector("ACME")).thenReturn("Semiconductors");

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        var headlines = (List<Map<String, Object>>) events.get(0).detail().get("headlines");
        assertThat(headlines.get(0)).containsEntry("credibility", 0.9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void macroTextDedupKeepsHigherCredibilityInstanceDeterministically() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100), position("BETA", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles(anyString())).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"), 0.2)));
        when(cd.news(eq("BETA"), any(), any())).thenReturn(List.of(
                macroHeadline("  FED RAISES RATES AGAIN ", Instant.parse("2026-06-03T12:00:00Z"), 0.9)));
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        var headlines = (List<Map<String, Object>>) events.get(0).detail().get("headlines");
        assertThat(headlines).hasSize(1);
        assertThat(headlines.get(0)).containsEntry("credibility", 0.9);
    }

    @Test
    void macroCooldownFromPersistedAlertRowSuppressesSecondTrigger() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"))));
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        var now = Instant.parse("2026-06-03T18:00:00Z");
        // seed the ALERT ROW — the only durable cooldown carrier (spec §9): 2h ago, inside 8h
        when(al.lastAlertAtAnyOwner("PORTFOLIO", "MACRO_PORTFOLIO"))
                .thenReturn(Optional.of(now.minusSeconds(7200)));
        when(al.lastAlertAtAnyOwner(eq("ACME"), anyString())).thenReturn(Optional.empty());

        assertThat(engine(hp, wl, in, cd, fi, al).detect(null, now)).isEmpty();
    }

    @Test
    void inMemoryGuardSuppressesSecondPollWhileFirstRunIsInFlight() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"))));
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        // NO persisted row yet (alert rows are written only at LLM completion)
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var engine = engine(hp, wl, in, cd, fi, al); // SAME instance across both polls
        var now = Instant.parse("2026-06-03T18:00:00Z");
        assertThat(engine.detect(null, now)).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.MACRO_PORTFOLIO);
        assertThat(engine.detect(null, now.plusSeconds(300))).isEmpty();
    }

    @Test
    void emptyDepotDropsMacroHeadlinesWithoutTrigger() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of());
        when(wl.distinctSweepRows()).thenReturn(List.of(
                new de.visterion.dracul.watchlist.WatchlistRepository.SweepRow("WTCH", "Watch Co", 50.0)));
        when(in.candles("WTCH")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("WTCH"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"))));
        when(cd.recommendations("WTCH")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z")))
                .isEmpty();
    }

    @Test
    void duplicateHeadlineUnderTwoSymbolsDedupsDeterministically() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100), position("AAPL", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles(anyString())).thenReturn(new IntradayCandles(List.of(), List.of()));
        // SAME text under both symbols (case/whitespace differ) + one older distinct headline
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z")),
                macroHeadline("Tariffs announced on imports", Instant.parse("2026-06-03T10:00:00Z"))));
        when(cd.news(eq("AAPL"), any(), any())).thenReturn(List.of(
                macroHeadline("  FED RAISES RATES AGAIN ", Instant.parse("2026-06-03T12:00:00Z"))));
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        @SuppressWarnings("unchecked")
        var headlines = (List<Map<String, Object>>) events.get(0).detail().get("headlines");
        // deduped to 2, datetime DESC: newest first
        assertThat(headlines).hasSize(2);
        assertThat(headlines.get(0).get("headline").toString().toLowerCase().trim())
                .isEqualTo("fed raises rates again");
        assertThat(headlines.get(1)).containsEntry("headline", "Tariffs announced on imports");
    }

    @Test
    void snapshotEntryWithMissingQuantityHasNullGainLossPct() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        // held position with NULL quantity: per-unit price underivable -> gain_loss_pct null
        // (spec §9 Snapshot P&L), direction null too; the entry itself still appears.
        var noQty = new HeldPosition("NOQT", null, BigDecimal.valueOf(100),
                BigDecimal.valueOf(1000), BigDecimal.ZERO, "USD",
                null, null, null, null, null, null, null, null);
        when(hp.openPositions("depot-1")).thenReturn(List.of(noQty));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("NOQT")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(cd.news(eq("NOQT"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"))));
        when(cd.recommendations("NOQT")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.MACRO_PORTFOLIO);
        var entry = events.get(0).portfolioSnapshot().get(0);
        assertThat(entry).containsEntry("symbol", "NOQT");
        assertThat(entry.get("gain_loss_pct")).isNull();
        assertThat(entry.get("direction")).isNull();
    }

    @Test
    void bucketIsCappedAtTenAfterDedup() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("ACME")).thenReturn(new IntradayCandles(List.of(), List.of()));
        // 12 DISTINCT macro headlines (+1 duplicate of the first) -> dedup 12 -> cap 10
        var headlines = new java.util.ArrayList<NewsHeadline>();
        for (int i = 0; i < 12; i++) {
            headlines.add(macroHeadline("Fed raises rates round " + i,
                    Instant.parse("2026-06-03T12:00:00Z").plusSeconds(i)));
        }
        headlines.add(macroHeadline("Fed raises rates round 0", Instant.parse("2026-06-03T12:00:00Z")));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(headlines);
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).hasSize(1);
        @SuppressWarnings("unchecked")
        var bucket = (List<Map<String, Object>>) events.get(0).detail().get("headlines");
        assertThat(bucket).hasSize(10);
        // datetime DESC: the newest (round 11) leads
        assertThat(bucket.get(0)).containsEntry("headline", "Fed raises rates round 11");
    }

    @Test
    void budgetExhaustionStillEmitsMacroPortfolioFromCompletedFutures() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("FAST", 100), position("SLOW", 100)));
        when(wl.distinctSweepRows()).thenReturn(List.of());
        when(in.candles("FAST")).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(in.candles("SLOW")).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return new IntradayCandles(List.of(), List.of());
        });
        when(cd.news(eq("FAST"), any(), any())).thenReturn(List.of(
                macroHeadline("Fed raises rates again", Instant.parse("2026-06-03T12:00:00Z"))));
        when(cd.news(eq("SLOW"), any(), any())).thenReturn(List.of());
        when(cd.recommendations(anyString())).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al, 500).detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        // partial-sweep rule (round 1, C1): macro news is market-wide — emit from what completed
        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.MACRO_PORTFOLIO);
    }

    @Test
    void depotOnlyNeverQueriesWatchlistAndSweepsOnlyDepot() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 105), List.of()));
        when(cd.news(eq("ACME"), any(), any())).thenReturn(List.of());
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());

        var events = engine(hp, wl, in, cd, fi, al, 60_000, "false")
                .detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).extracting(TriggerEvent::triggerType).containsExactly(TriggerType.PRICE_SPIKE);
        verify(wl, never()).distinctSweepRows();
        verify(in, never()).candles("WTCH");
    }

    @Test
    void depotOnlyEmptyDepotNoOpsWithNoAgoraCalls() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        when(hp.openPositions("depot-1")).thenReturn(List.of());

        var events = engine(hp, wl, in, cd, fi, al, 60_000, "false")
                .detect(null, Instant.parse("2026-06-03T18:00:00Z"));

        assertThat(events).isEmpty();
        verify(wl, never()).distinctSweepRows();
        verifyNoInteractions(in, cd, fi);
    }

    @Test
    void depotOnlyStillEmitsMacroPortfolioForHeldSymbol() {
        var hp = mock(HeldPositionService.class);
        var wl = mock(de.visterion.dracul.watchlist.WatchlistRepository.class);
        var in = mock(AgoraIntraday.class);
        var cd = mock(AgoraCompanyData.class);
        var fi = mock(AgoraFilings.class);
        var al = mock(DaywalkerAlertRepository.class);

        var now = Instant.parse("2026-06-03T18:00:00Z");
        when(hp.openPositions("depot-1")).thenReturn(List.of(position("ACME", 100)));
        when(in.candles("ACME")).thenReturn(new IntradayCandles(closes(100, 100), List.of()));
        when(cd.recommendations("ACME")).thenReturn(List.of());
        when(fi.recentForm4(any(), any())).thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(al.lastAlertAtAnyOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(cd.news(eq("ACME"), any(), any()))
                .thenReturn(List.of(macroHeadline("Fed raises rates again", now.minusSeconds(120))));

        var events = engine(hp, wl, in, cd, fi, al, 60_000, "false").detect(null, now);

        assertThat(events).extracting(TriggerEvent::triggerType).contains(TriggerType.MACRO_PORTFOLIO);
        verify(wl, never()).distinctSweepRows();
    }
}
