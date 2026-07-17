package de.visterion.dracul.renfield;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.hunting.agora.SectorResolver;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.position.PortfolioWeights;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenfieldSchedulerTest {

    private final WatchlistRepository watchlist = mock(WatchlistRepository.class);
    private final AgoraMarketData marketData = mock(AgoraMarketData.class);
    private final AgoraCompanyData companyData = mock(AgoraCompanyData.class);
    private final DaywalkerAlertRepository alerts = mock(DaywalkerAlertRepository.class);
    private final VerdictRepository verdicts = mock(VerdictRepository.class);
    private final HeldPositionService heldPositions = mock(HeldPositionService.class);
    private final PortfolioWeights portfolioWeights = mock(PortfolioWeights.class);
    private final SectorResolver sectors = mock(SectorResolver.class);
    private final VistierieClient vistierie = mock(VistierieClient.class);

    private RenfieldScheduler scheduler() {
        return new RenfieldScheduler(watchlist, marketData, companyData, alerts, verdicts,
                heldPositions, portfolioWeights, sectors, vistierie,
                "http://localhost:8080", "ren-tkn", "depot-1", "primary@x.com");
    }

    private static WatchlistItem item(String ticker, String verdictId) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Corp", 41.0, -1.2,
                "calm", "2026-07-01", "TRACKING", verdictId, List.of(), List.of(),
                null, null, "primary@x.com", "USD", null);
    }

    private static HeldPosition held(String symbol) {
        return new HeldPosition(symbol, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, "USD", null, null, null, null, null, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assemblesInputAndTriggersRunWithCompletionWebhook() {
        when(watchlist.findAllByUser("primary@x.com")).thenReturn(List.of(item("ACME", "v-1")));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of("ACME",
                new Quote(new BigDecimal("42.50"), new BigDecimal("-2.1"))));
        when(companyData.news(eq("ACME"), any(), any())).thenReturn(List.of(
                new NewsHeadline("ACME cuts guidance", "outlook lowered", "wire", "news",
                        Instant.parse("2026-07-17T09:00:00Z"), null)));
        when(alerts.recentAlerts(eq("ACME"), any())).thenReturn(List.of(
                new DaywalkerAlertRepository.RecentAlert("NEGATIVE_NEWS", "WARNING", "guidance cut",
                        Instant.parse("2026-07-17T10:00:00Z"))));
        when(verdicts.findLatestBySymbol("ACME")).thenReturn(Optional.of(
                new VerdictRepository.LatestVerdictForSymbol("v-1", "swing", "spin-off setup",
                        List.of("sig"), List.of("risk"), List.of("SPIN_OFF"))));
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of(held("ACME")));
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of("ACME", new BigDecimal("100.0")));
        when(sectors.sector("ACME")).thenReturn("Semiconductors");

        scheduler().run();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(vistierie).triggerRun(eq("renfield"), captor.capture(),
                eq("http://localhost:8080/api/renfield/complete"), eq("ren-tkn"));
        Map<String, Object> input = captor.getValue();
        assertThat(input).containsKey("as_of");
        var symbols = (List<Map<String, Object>>) input.get("symbols");
        assertThat(symbols).hasSize(1);
        Map<String, Object> acme = symbols.get(0);
        assertThat(acme).containsEntry("symbol", "ACME")
                .containsEntry("current_price", new BigDecimal("42.50"))
                .containsEntry("day_change_percent", new BigDecimal("-2.1"))
                .doesNotContainKey("held");
        var news = (List<Map<String, Object>>) acme.get("news");
        assertThat(news).hasSize(1);
        assertThat(news.get(0)).containsEntry("headline", "ACME cuts guidance");
        assertThat((String) news.get(0).get("event_tags")).contains("guidance_cut");
        var alertList = (List<Map<String, Object>>) acme.get("alerts");
        assertThat(alertList).hasSize(1);
        assertThat(alertList.get(0)).containsEntry("trigger_type", "NEGATIVE_NEWS");
        var verdict = (Map<String, Object>) acme.get("verdict");
        assertThat(verdict).containsEntry("summary", "spin-off setup");
        @SuppressWarnings("unchecked")
        var position = (Map<String, Object>) acme.get("position");
        assertThat(position).isNotNull();
        assertThat(position).containsEntry("direction", "long")
                .containsEntry("sector", "Semiconductors");
        assertThat((BigDecimal) position.get("entry")).isEqualByComparingTo("10");
        // held(..) helper: qty 1, marketValue 10 -> per-unit 10 vs entry 10 -> 0 (C1 snapshot formula)
        assertThat((BigDecimal) position.get("gain_loss_pct")).isEqualByComparingTo("0");
        assertThat((BigDecimal) position.get("weight_pct")).isEqualByComparingTo("100.0");
        assertThat(position).containsKey("active_stop");
        assertThat(acme).doesNotContainKey("sector"); // held entries carry sector only inside the block
    }

    @Test
    void emptyWatchlistSkipsEntirely() {
        when(watchlist.findAllByUser("primary@x.com")).thenReturn(List.of());

        scheduler().run();

        verify(vistierie, never()).triggerRun(anyString(), any(), any(), any());
        verifyNoInteractions(marketData, companyData, heldPositions);
    }

    @Test
    void vistierieUnreachableWarnsAndSurvives() {
        when(watchlist.findAllByUser("primary@x.com")).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of());
        when(sectors.sector(anyString())).thenReturn(null);
        when(vistierie.triggerRun(anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("vistierie down"));

        assertThatCode(() -> scheduler().run()).doesNotThrowAnyException();
    }

    @Test
    void missingQuoteFallsBackToStoredWatchlistPrice() {
        when(watchlist.findAllByUser("primary@x.com")).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of());
        when(sectors.sector(anyString())).thenReturn(null);

        scheduler().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(vistierie).triggerRun(eq("renfield"), captor.capture(), any(), any());
        @SuppressWarnings("unchecked")
        var symbols = (List<Map<String, Object>>) captor.getValue().get("symbols");
        assertThat(symbols.get(0)).containsEntry("current_price", 41.0)
                .doesNotContainKey("verdict");
    }

    @Test
    void notHeldSymbolHasNoPositionKeyAndCarriesTopLevelSector() {
        when(watchlist.findAllByUser("primary@x.com")).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of());
        when(sectors.sector("ACME")).thenReturn("Utilities");

        scheduler().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(vistierie).triggerRun(eq("renfield"), captor.capture(), any(), any());
        @SuppressWarnings("unchecked")
        var symbols = (List<Map<String, Object>>) captor.getValue().get("symbols");
        assertThat(symbols.get(0)).doesNotContainKey("position")
                .doesNotContainKey("held")
                .containsEntry("sector", "Utilities");
    }

    @Test
    void unresolvedSectorOmitsTheTopLevelKey() {
        when(watchlist.findAllByUser("primary@x.com")).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of());
        when(sectors.sector("ACME")).thenReturn(null);

        scheduler().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(vistierie).triggerRun(eq("renfield"), captor.capture(), any(), any());
        @SuppressWarnings("unchecked")
        var symbols = (List<Map<String, Object>>) captor.getValue().get("symbols");
        assertThat(symbols.get(0)).doesNotContainKey("sector");
    }
}
