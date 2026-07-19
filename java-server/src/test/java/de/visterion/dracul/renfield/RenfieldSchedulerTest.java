package de.visterion.dracul.renfield;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hivemem.MemoryHit;
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
import java.util.ArrayList;
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
    private final HiveMemResearchService memory = mock(HiveMemResearchService.class);

    private RenfieldScheduler scheduler() {
        return scheduler(30);
    }

    private RenfieldScheduler scheduler(int maxSymbols) {
        return scheduler(maxSymbols, 2000L);
    }

    private RenfieldScheduler scheduler(int maxSymbols, long priorMemoryBudgetMs) {
        return new RenfieldScheduler(watchlist, marketData, companyData, alerts, verdicts,
                heldPositions, portfolioWeights, sectors, vistierie, memory,
                "http://localhost:8080", "ren-tkn", "depot-1", "primary@x.com",
                maxSymbols, priorMemoryBudgetMs);
    }

    private static WatchlistItem item(String ticker, String verdictId) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Corp", 41.0, -1.2,
                "calm", "2026-07-01", "TRACKING", verdictId, List.of(), List.of(),
                null, null, "primary@x.com", "USD", null);
    }

    /** Full-control constructor for priority/cap tests: explicit tag, source, addedAt. */
    private static WatchlistItem item(String ticker, String tag, String verdictId, String source, String addedAt) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Corp", 41.0, -1.2,
                "calm", addedAt, tag, verdictId, List.of(), List.of(),
                null, null, "primary@x.com", "USD", null,
                41.0, "USD", null, source);
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
                        Instant.parse("2026-07-17T09:00:00Z"), null, "reuters.com", 0.9)));
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
        assertThat(news.get(0)).containsEntry("credibility", 0.9);
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
    @SuppressWarnings("unchecked")
    void capsAndPrioritizesWhenOverLimit() {
        WatchlistItem held = item("HELD1", "HELD", null, "manual", "2026-01-01");
        WatchlistItem verdict = item("VERD1", null, "v-1", "manual", "2026-01-01");
        WatchlistItem agent = item("AGT1", null, null, "agent:pead", "2026-01-01");
        WatchlistItem manual = item("MAN1", null, null, "manual", "2026-01-01");
        WatchlistItem seed = item("SEED1", null, null, "seed", "2026-01-01");

        List<WatchlistItem> items = new ArrayList<>(List.of(held, verdict, agent, manual, seed));
        // 26 "else" items (no tag/verdict/known source) with distinct addedAt for tie-break.
        // Newest survives, oldest ("2026-06-01" = ELSE00) is the one dropped by the cap.
        List<String> elseTickers = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            String ticker = "ELSE" + String.format("%02d", i);
            elseTickers.add(ticker);
            items.add(item(ticker, null, null, "unknown", String.format("2026-06-%02d", i + 1)));
        }
        assertThat(items).hasSize(31);

        when(watchlist.findAllByUser("primary@x.com")).thenReturn(items);
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(verdicts.findLatestBySymbol(anyString())).thenReturn(Optional.empty());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RenfieldScheduler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler(30).run();

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(vistierie).triggerRun(eq("renfield"), captor.capture(), any(), any());
            var symbols = (List<Map<String, Object>>) captor.getValue().get("symbols");
            assertThat(symbols).hasSize(30);
            var order = symbols.stream().map(m -> (String) m.get("symbol")).toList();

            assertThat(order.get(0)).isEqualTo("HELD1");
            assertThat(order.get(1)).isEqualTo("VERD1");
            assertThat(order.get(2)).isEqualTo("AGT1");
            assertThat(order.get(3)).isEqualTo("MAN1");
            assertThat(order.get(4)).isEqualTo("SEED1");
            // else-stage items follow, newest addedAt first (ELSE25 .. ELSE01); ELSE00 dropped.
            List<String> expectedElseOrder = new ArrayList<>(elseTickers.subList(1, 26));
            java.util.Collections.reverse(expectedElseOrder);
            assertThat(order.subList(5, 30)).isEqualTo(expectedElseOrder);
            assertThat(order).doesNotContain("ELSE00");

            assertThat(appender.list).anySatisfy(ev -> {
                assertThat(ev.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(ev.getFormattedMessage())
                        .contains("capped watchlist review to 30 of 31 symbols (dropped 1)");
            });
            assertThat(appender.list).anySatisfy(ev ->
                    assertThat(ev.getFormattedMessage())
                            .contains("renfield review triggered for 30 watchlist symbols"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void manualSourceWithVerdictIdRanksAsVerdictStage() {
        // source=manual but verdictId!=null must NOT be treated as the manual stage (rank 3);
        // it counts as the verdict stage (rank 1) -- verdict merges onto manual rows.
        WatchlistItem manualVerdict = item("MV1", null, "v-9", "manual", "2026-01-01");
        WatchlistItem plainAgent = item("AG1", null, null, "agent:pead", "2026-06-01");

        List<WatchlistItem> items = new ArrayList<>(List.of(plainAgent, manualVerdict));
        for (int i = 0; i < 29; i++) {
            items.add(item("FILL" + i, null, null, "unknown", "2026-05-01"));
        }
        assertThat(items).hasSize(31);

        when(watchlist.findAllByUser("primary@x.com")).thenReturn(items);
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(verdicts.findLatestBySymbol(anyString())).thenReturn(Optional.empty());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());

        scheduler(30).run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(vistierie).triggerRun(eq("renfield"), captor.capture(), any(), any());
        @SuppressWarnings("unchecked")
        var symbols = (List<Map<String, Object>>) captor.getValue().get("symbols");
        var order = symbols.stream().map(m -> (String) m.get("symbol")).toList();

        // manual+verdictId (MV1, rank 1) must be reviewed before the agent item (AG1, rank 2),
        // even though AG1 has a much newer addedAt -- rank wins over tie-break.
        assertThat(order.indexOf("MV1")).isLessThan(order.indexOf("AG1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void atOrBelowCapLeavesOrderUntouchedAndDoesNotLog() {
        List<WatchlistItem> items = new ArrayList<>();
        // Deliberately mixed priority/addedAt order that a sort WOULD reshuffle, to prove
        // the no-cap path leaves findAllByUser's order (added_at DESC) untouched.
        items.add(item("SEEDX", null, null, "seed", "2026-01-01"));
        items.add(item("HELDX", "HELD", null, "manual", "2026-01-02"));
        for (int i = 0; i < 28; i++) {
            items.add(item("ITEM" + i, null, null, "unknown", "2026-01-03"));
        }
        assertThat(items).hasSize(30);
        List<String> expectedOrder = items.stream().map(WatchlistItem::ticker).toList();

        when(watchlist.findAllByUser("primary@x.com")).thenReturn(items);
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(verdicts.findLatestBySymbol(anyString())).thenReturn(Optional.empty());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RenfieldScheduler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler(30).run();

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(vistierie).triggerRun(eq("renfield"), captor.capture(), any(), any());
            var symbols = (List<Map<String, Object>>) captor.getValue().get("symbols");
            assertThat(symbols).hasSize(30);
            var order = symbols.stream().map(m -> (String) m.get("symbol")).toList();
            assertThat(order).isEqualTo(expectedOrder);

            assertThat(appender.list).noneMatch(ev -> ev.getFormattedMessage().contains("capped"));
            assertThat(appender.list).anySatisfy(ev ->
                    assertThat(ev.getFormattedMessage())
                            .contains("renfield review triggered for 30 watchlist symbols"));
        } finally {
            logger.detachAppender(appender);
        }
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

    /** Task 11: prior_memory pre-fetch — healthy HiveMem populates every symbol within budget. */
    @Test
    @SuppressWarnings("unchecked")
    void assembleInputPopulatesPriorMemoryForEverySymbolWhenHiveMemIsHealthy() {
        List<WatchlistItem> items = List.of(item("ACME", null), item("BETA", null));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of());
        when(sectors.sector(anyString())).thenReturn(null);
        when(memory.searchForInput(eq("ACME"), eq(3)))
                .thenReturn(List.of(new MemoryHit("id-1", "sum-acme", "content-acme")));
        when(memory.searchForInput(eq("BETA"), eq(3)))
                .thenReturn(List.of(new MemoryHit("id-2", "sum-beta", "content-beta")));

        var input = scheduler(30, 2000L).assembleInput(items, Instant.now());

        var symbols = (List<Map<String, Object>>) input.get("symbols");
        assertThat(symbols).hasSize(2);
        var acmeMemory = (List<Map<String, Object>>) symbols.get(0).get("prior_memory");
        assertThat(acmeMemory).hasSize(1);
        assertThat(acmeMemory.get(0)).containsEntry("summary", "sum-acme")
                .containsEntry("content", "content-acme");
        var betaMemory = (List<Map<String, Object>>) symbols.get(1).get("prior_memory");
        assertThat(betaMemory).hasSize(1);
        assertThat(betaMemory.get(0)).containsEntry("summary", "sum-beta")
                .containsEntry("content", "content-beta");
    }

    /** Task 11: a black-holing HiveMem (hangs, never throws) must not blow the scheduler's total
     *  wall-clock past the configured budget (+ small slack) -- NOT budget x symbol-count -- and
     *  symbols reviewed after the deadline elapses degrade to an empty prior_memory. */
    @Test
    @SuppressWarnings("unchecked")
    void assembleInputStaysWallClockBoundedWhenHiveMemBlackHoles() {
        List<WatchlistItem> items = List.of(item("A", null), item("B", null), item("C", null));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of());
        when(sectors.sector(anyString())).thenReturn(null);
        when(memory.searchForInput(anyString(), eq(3))).thenAnswer(inv -> {
            Thread.sleep(300);
            return List.of();
        });

        long budgetMs = 50L;
        long startNanos = System.nanoTime();
        var input = scheduler(30, budgetMs).assembleInput(items, Instant.now());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // bounded by ~one blocking call, not by 3x the black-hole sleep.
        assertThat(elapsedMs).isLessThan(300 + 250);
        verify(memory, times(1)).searchForInput(anyString(), eq(3));

        var symbols = (List<Map<String, Object>>) input.get("symbols");
        assertThat((List<Object>) symbols.get(1).get("prior_memory")).isEmpty();
        assertThat((List<Object>) symbols.get(2).get("prior_memory")).isEmpty();
    }
}
