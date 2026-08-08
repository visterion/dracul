package de.visterion.dracul.renfield;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hivemem.MemoryHit;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.hunting.agora.SectorResolver;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.position.PortfolioWeights;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.vistierie.VistierieRunDetail;
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
    private final TradeProposalRepository proposals = mock(TradeProposalRepository.class);
    private final RenfieldRunContextRepository runContext = mock(RenfieldRunContextRepository.class);
    private final FxService fx = mock(FxService.class);

    private static final String OWNER = "alice@example.com";

    private RenfieldScheduler scheduler() {
        return scheduler(30);
    }

    private RenfieldScheduler scheduler(int maxSymbols) {
        return scheduler(maxSymbols, 2000L);
    }

    private RenfieldScheduler scheduler(int maxSymbols, long priorMemoryBudgetMs) {
        return new RenfieldScheduler(watchlist, marketData, companyData, alerts, verdicts,
                heldPositions, portfolioWeights, sectors, vistierie, memory,
                proposals, runContext, fx,
                "http://localhost:8080", "ren-tkn", "depot-1", OWNER,
                maxSymbols, priorMemoryBudgetMs);
    }

    /** Depot answered (available = true) with the given positions. */
    private void stubDepot(List<HeldPosition> positions) {
        when(heldPositions.openPositionsOrUnavailable("depot-1"))
                .thenReturn(new HeldPositionService.OpenPositions(positions, true));
    }

    /** Depot read failed: no positions AND no answer -- must not read like an empty depot. */
    private void stubDepotUnavailable() {
        when(heldPositions.openPositionsOrUnavailable("depot-1"))
                .thenReturn(new HeldPositionService.OpenPositions(List.of(), false));
    }

    /** A HELD watchlist row of the primary owner, with the position fields under test. */
    private static WatchlistItem heldItem(String ticker, Double entryPrice, Double shareCount,
            String currency, String entryCurrency) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Corp", 100.0, -1.2,
                "calm", "2026-07-01", "HELD", null, List.of(), List.of(),
                entryPrice, shareCount, OWNER, currency, entryCurrency, "manual");
    }

    private static WatchlistItem item(String ticker, String verdictId) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Corp", 41.0, -1.2,
                "calm", "2026-07-01", "TRACKING", verdictId, List.of(), List.of(),
                null, null, OWNER, "USD", null);
    }

    /** Full-control constructor for priority/cap tests: explicit tag, source, addedAt. */
    private static WatchlistItem item(String ticker, String tag, String verdictId, String source, String addedAt) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Corp", 41.0, -1.2,
                "calm", addedAt, tag, verdictId, List.of(), List.of(),
                null, null, OWNER, "USD", null,
                41.0, "USD", null, source);
    }

    private static HeldPosition held(String symbol) {
        return new HeldPosition(symbol, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, "USD", null, null, null, null, null, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assemblesInputAndTriggersRunWithCompletionWebhook() {
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of(item("ACME", "v-1")));
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
        stubDepot(List.of(held("ACME")));
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
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of());

        scheduler().run();

        verify(vistierie, never()).triggerRun(anyString(), any(), any(), any());
        verifyNoInteractions(marketData, companyData, heldPositions);
    }

    @Test
    void vistierieUnreachableWarnsAndSurvives() {
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        stubDepot(List.of());
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

        when(watchlist.findAllByUser(OWNER)).thenReturn(items);
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(verdicts.findLatestBySymbol(anyString())).thenReturn(Optional.empty());
        stubDepot(List.of());

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

        when(watchlist.findAllByUser(OWNER)).thenReturn(items);
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(verdicts.findLatestBySymbol(anyString())).thenReturn(Optional.empty());
        stubDepot(List.of());

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

        when(watchlist.findAllByUser(OWNER)).thenReturn(items);
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(verdicts.findLatestBySymbol(anyString())).thenReturn(Optional.empty());
        stubDepot(List.of());

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
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        stubDepot(List.of());
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
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        stubDepot(List.of());
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
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of(item("ACME", null)));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        stubDepot(List.of());
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
        stubDepot(List.of());
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
        stubDepot(List.of());
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

    // --- Task 6 (F1/F2/F4): what the user holds, in which currency, and what was said before ---

    /** Baseline stubs for the payload tests: quiet market, quiet depot, no memory. */
    private void quietWorld() {
        when(marketData.quotes(anyCollection())).thenReturn(Map.of());
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(alerts.recentAlerts(anyString(), any())).thenReturn(List.of());
        when(portfolioWeights.weightsBySymbol(any())).thenReturn(Map.of());
        when(sectors.sector(anyString())).thenReturn(null);
        when(memory.searchForInput(anyString(), anyInt())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> symbolsOf(Map<String, Object> input) {
        return (List<Map<String, Object>>) input.get("symbols");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> holdingOf(Map<String, Object> symbol) {
        return (Map<String, Object>) symbol.get("holding");
    }

    /** F1: a HELD row carries the user's own entry into the payload -- with BOTH currencies,
     *  so the agent can never read a EUR entry against a USD quote as a gain. */
    @Test
    void heldWatchlistRowProducesHoldingBlock() {
        quietWorld();
        stubDepot(List.of());
        List<WatchlistItem> items = List.of(heldItem("NVDA", 162.20, 10.0, "USD", "EUR"));

        var holding = holdingOf(symbolsOf(scheduler().assembleInput(items, Instant.now())).get(0));

        assertThat(holding).containsEntry("entry_price", 162.20)
                .containsEntry("share_count", 10.0)
                .containsEntry("entry_currency", "EUR")
                .containsEntry("currency", "USD");
    }

    /** F1: most HELD rows have neither entry price nor share count. "Held, details unknown"
     *  must still be visible -- an absent block would read as "not held". */
    @Test
    void heldWithoutEntryPriceStillProducesTheBlock() {
        quietWorld();
        stubDepot(List.of());
        List<WatchlistItem> items = List.of(heldItem("ABBNY", null, null, "USD", null));

        var symbol = symbolsOf(scheduler().assembleInput(items, Instant.now())).get(0);

        assertThat(symbol).containsKey("holding");
        assertThat(holdingOf(symbol)).containsEntry("currency", "USD")
                .doesNotContainKey("entry_price")
                .doesNotContainKey("share_count")
                .doesNotContainKey("entry_price_in_currency")
                .doesNotContainKey("gain_loss_pct");
    }

    /** F1: no cached rate => no percentage at all. Deciding on convert()'s return value would
     *  hand back the unchanged EUR amount and label it USD -- a fabricated +38 %. */
    @Test
    void differingCurrenciesWithoutRateOmitGainLossPct() {
        quietWorld();
        stubDepot(List.of());
        when(marketData.quotes(anyCollection())).thenReturn(
                Map.of("TSM", new Quote(new BigDecimal("419.92"), new BigDecimal("0.4"))));
        when(fx.hasRate("EUR", "USD")).thenReturn(false);
        // convert() would answer 330.76 unchanged; the code must never ask.
        when(fx.convert(any(), eq("EUR"), eq("USD"))).thenReturn(new BigDecimal("330.76"));
        List<WatchlistItem> items = List.of(heldItem("TSM", 330.76, 5.0, "USD", "EUR"));

        var holding = holdingOf(symbolsOf(scheduler().assembleInput(items, Instant.now())).get(0));

        assertThat(holding).containsEntry("entry_price", 330.76)
                .containsEntry("entry_currency", "EUR")
                .containsEntry("currency", "USD")
                .doesNotContainKey("entry_price_in_currency")
                .doesNotContainKey("gain_loss_pct");
    }

    /** F1: with a real rate the entry is restated in the quote currency and the percentage
     *  is computed against THAT, not against the raw foreign-currency entry. */
    @Test
    void differingCurrenciesWithRateEmitConvertedEntry() {
        quietWorld();
        stubDepot(List.of());
        when(marketData.quotes(anyCollection())).thenReturn(
                Map.of("NVDA", new Quote(new BigDecimal("220.00"), new BigDecimal("0.4"))));
        when(fx.hasRate("EUR", "USD")).thenReturn(true);
        when(fx.convert(any(), eq("EUR"), eq("USD"))).thenReturn(new BigDecimal("200.0000"));
        List<WatchlistItem> items = List.of(heldItem("NVDA", 170.00, 10.0, "USD", "EUR"));

        var holding = holdingOf(symbolsOf(scheduler().assembleInput(items, Instant.now())).get(0));

        assertThat((BigDecimal) holding.get("entry_price_in_currency")).isEqualByComparingTo("200.0000");
        // 220 vs 200 in USD -> +10 %, NOT 220 vs 170.
        assertThat((BigDecimal) holding.get("gain_loss_pct")).isEqualByComparingTo("10.00");
    }

    /** "The depot is down" must never read like "the depot is empty": the payload says which. */
    @Test
    void depotUnavailableSetsPositionSourceUnavailable() {
        quietWorld();
        stubDepotUnavailable();
        List<WatchlistItem> items = List.of(heldItem("TSM", 330.76, 5.0, "USD", "EUR"));

        var input = scheduler().assembleInput(items, Instant.now());

        assertThat(input).containsEntry("position_source", "unavailable");
        assertThat(symbolsOf(input).get(0)).containsKey("holding").doesNotContainKey("position");
    }

    /** The other half of the pair: depot answered, this symbol simply is not in it. */
    @Test
    void depotOkWithoutMatchSetsPositionSourceOk() {
        quietWorld();
        stubDepot(List.of(held("OTHER")));
        List<WatchlistItem> items = List.of(heldItem("TSM", 330.76, 5.0, "USD", "EUR"));

        var input = scheduler().assembleInput(items, Instant.now());

        assertThat(input).containsEntry("position_source", "ok");
        assertThat(symbolsOf(input).get(0)).doesNotContainKey("position");
    }

    /** The holding comes from the owner-scoped list only. A row of a second account
     *  contributes no entry price and no share count, whatever put it into the list. */
    @Test
    void holdingIsBuiltOnlyFromTheOwnerScopedItems() {
        quietWorld();
        stubDepot(List.of());
        WatchlistItem foreign = new WatchlistItem("id-FRGN", "FRGN", "Foreign Corp", 50.0, 0.1,
                "calm", "2026-07-01", "HELD", null, List.of(), List.of(),
                99.99, 42.0, "mallory@example.com", "USD", "USD", "manual");
        List<WatchlistItem> items = List.of(heldItem("TSM", 330.76, 5.0, "USD", "EUR"), foreign);

        var symbols = symbolsOf(scheduler().assembleInput(items, Instant.now()));

        assertThat(symbols.get(0)).containsKey("holding");
        assertThat(symbols.get(1)).containsEntry("symbol", "FRGN").doesNotContainKey("holding");
        assertThat(symbols.toString()).doesNotContain("99.99").doesNotContain("42.0");
    }

    /** F2: one batched query for the whole review, not one per symbol. */
    @Test
    @SuppressWarnings("unchecked")
    void priorProposalsAreFetchedInASingleCall() {
        quietWorld();
        stubDepot(List.of());
        when(proposals.findPriorBySymbols(eq(OWNER), anyList())).thenReturn(Map.of(
                "AVGO", List.of(new PriorProposal("2026-08-07T12:00:00Z", "buy", new BigDecimal("0.7")),
                        new PriorProposal("2026-08-06T12:00:00Z", "buy", new BigDecimal("0.6")))));
        List<WatchlistItem> items = List.of(item("AVGO", null), item("BETA", null));

        var symbols = symbolsOf(scheduler().assembleInput(items, Instant.now()));

        verify(proposals, times(1)).findPriorBySymbols(eq(OWNER), anyList());
        var avgo = (List<Map<String, Object>>) symbols.get(0).get("prior_proposals");
        assertThat(avgo).hasSize(2);
        assertThat(avgo.get(0)).containsEntry("date", "2026-08-07T12:00:00Z")
                .containsEntry("action", "buy")
                .containsEntry("confidence", new BigDecimal("0.7"));
        assertThat((List<Object>) symbols.get(1).get("prior_proposals")).isEmpty();
    }

    /** F4: the snapshot the completion-time action check judges against is written per symbol
     *  at trigger time, keyed by the run id the trigger returned -- and old rows are swept. */
    @Test
    void runContextIsPersistedForEverySymbol() {
        quietWorld();
        stubDepot(List.of());
        when(watchlist.findAllByUser(OWNER)).thenReturn(
                List.of(heldItem("TSM", 330.76, 5.0, "USD", "EUR"), item("BETA", null)));
        when(vistierie.triggerRun(anyString(), any(), any(), any()))
                .thenReturn(new VistierieRunDetail("run-7", "renfield", "running", null, null, null, null));

        scheduler().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Boolean>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runContext).save(eq("run-7"), captor.capture(), eq("ok"));
        assertThat(captor.getValue()).containsEntry("TSM", true).containsEntry("BETA", false);
        verify(runContext).deleteOlderThan(30);
    }

    /** No run id (an unexpected Vistierie answer) must not blow up the trigger, and must not
     *  write a snapshot under a bogus key either. */
    @Test
    void missingRunIdSkipsTheSnapshotWithoutFailingTheRun() {
        quietWorld();
        stubDepot(List.of());
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of(item("BETA", null)));
        when(vistierie.triggerRun(anyString(), any(), any(), any())).thenReturn(null);

        assertThatCode(() -> scheduler().run()).doesNotThrowAnyException();

        verifyNoInteractions(runContext);
    }

    /** A failing snapshot write must not be reported as a failed trigger -- the run is away. */
    @Test
    void snapshotFailureDoesNotMasqueradeAsAFailedTrigger() {
        quietWorld();
        stubDepot(List.of());
        when(watchlist.findAllByUser(OWNER)).thenReturn(List.of(item("BETA", null)));
        when(vistierie.triggerRun(anyString(), any(), any(), any()))
                .thenReturn(new VistierieRunDetail("run-8", "renfield", "running", null, null, null, null));
        doThrow(new RuntimeException("db down")).when(runContext).save(any(), any(), any());

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RenfieldScheduler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> scheduler().run()).doesNotThrowAnyException();

            assertThat(appender.list).anySatisfy(ev ->
                    assertThat(ev.getFormattedMessage()).contains("renfield review triggered"));
            assertThat(appender.list).noneMatch(ev ->
                    ev.getFormattedMessage().contains("renfield trigger failed"));
            assertThat(appender.list).anySatisfy(ev ->
                    assertThat(ev.getFormattedMessage()).contains("run-context snapshot for run run-8 failed"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
