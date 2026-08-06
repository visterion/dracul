package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraIndexConstituents;
import de.visterion.dracul.hunting.agora.AgoraPriceRange;
import de.visterion.dracul.hunting.agora.IndexConstituent;
import de.visterion.dracul.hunting.agora.PriceRange;
import de.visterion.dracul.hunting.agora.PriceRangeMocks;
import de.visterion.dracul.hunting.agora.RangeProbe;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * D7: strigoi-lazarus screens a MARKET-WIDE universe (the S&amp;P 500 via Agora's
 * {@code get_index_constituents}), not just the manually maintained watchlist — and it reports
 * honestly what it lost on the way.
 *
 * <p>The production bug this pins: with {@code watchlist_items} empty for user "default", every
 * lazarus run returned {@code candidates: []} with {@code data_source_health.status = "healthy"}.
 * A guaranteed no-op that looked like a quiet market.
 */
class StrigoiLazarusMarketUniverseTest {

    private static final String CONNECTION = "depot-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WatchlistRepository watchlist;
    private HeldPositionService heldPositionService;
    private AgoraCompanyData companyData;
    private AgoraIndexConstituents index;
    private AgoraPriceRange priceRange;
    private LazarusEnrichmentService enrichment;

    private StrigoiLazarusWebhookController controller;

    @BeforeEach
    void setUp() {
        watchlist = mock(WatchlistRepository.class);
        heldPositionService = mock(HeldPositionService.class);
        companyData = mock(AgoraCompanyData.class);
        index = mock(AgoraIndexConstituents.class);
        priceRange = PriceRangeMocks.batching();
        enrichment = mock(LazarusEnrichmentService.class);
        when(enrichment.enrich(any())).thenAnswer(i -> {
            List<LazarusCandidate> in = i.getArgument(0);
            return new EnrichedLazarusBatch(
                    in.stream().map(StrigoiLazarusMarketUniverseTest::enriched).toList(), 0);
        });

        when(watchlist.findAllByUser("default")).thenReturn(List.of());
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());
        when(companyData.fundamentals(anyString())).thenReturn(null);
        when(companyData.fundamentalsResult(anyString()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));

        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, new LazarusScreener(), enrichment,
                mock(PreyRepository.class), new ToolFetchCache(new AgentToolCatalog(List.of()), 0),
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION, "",
                0.10, 3.0, 2.0, 20, "AAPL",
                "sp500", 600, 0.25, 150_000L, 10, 60);
    }

    private static EnrichedLazarusCandidate enriched(LazarusCandidate c) {
        return new EnrichedLazarusCandidate(
                c.symbol(), c.companyName(), c.currentPrice(), c.week52Low(), c.week52High(),
                c.pctAboveLow(), c.roaTtm(), c.currentRatio(), c.debtToEquity(), c.grossMargin(),
                c.netMargin(), c.revenueGrowthYoy(), c.epsGrowthYoy(), c.priceToBook(), c.peTtm(),
                c.fcfPerShare(), 0, 0, null, false, false,
                null, null, null, false, null, false, null, null, 0, false);
    }

    private void indexReturns(String... symbols) {
        when(index.constituents("sp500")).thenReturn(DataSourceResult.healthy("agora",
                java.util.Arrays.stream(symbols)
                        .map(s -> new IndexConstituent(s, s + " Inc", "Industrials")).toList()));
    }

    /** Trades 5 % above its 52-week low of 10 — inside both the 0.25 pre-filter and the 0.10 screen. */
    private void nearLow(String symbol) {
        when(priceRange.range52w(symbol)).thenReturn(RangeProbe.of(new PriceRange(symbol,
                new BigDecimal("10.50"), BigDecimal.TEN, new BigDecimal("40"))));
    }

    private void farFromLow(String symbol) {
        when(priceRange.range52w(symbol)).thenReturn(RangeProbe.of(new PriceRange(symbol,
                new BigDecimal("30.00"), BigDecimal.TEN, new BigDecimal("40"))));
    }

    /** A clean, cheap, solvent quality name that survives every unchanged screen threshold. */
    private JsonNode goodFundamentals() {
        return MAPPER.readTree("""
                {"52WeekLow":10.0,"52WeekHigh":40.0,"roaTTM":5.0,"currentRatioQuarterly":1.8,
                 "totalDebt/totalEquityQuarterly":0.4,"grossMarginTTM":35.0,"netProfitMarginTTM":8.0,
                 "revenueGrowthTTMYoy":4.0,"epsGrowthTTMYoy":3.0,"pbAnnual":1.2,"peTTM":11.0,
                 "freeCashFlowPerShareTTM":2.3}
                """);
    }

    private WatchlistItem item(String ticker, double price) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Inc",
                price, 0.0, "ACTIVE", "2025-01-01T00:00:00Z", "WATCHED",
                null, List.of(), List.of(), null, null, "default", "USD", "USD");
    }

    private HeldPosition held(String symbol) {
        return new HeldPosition(symbol, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ZERO, null, null, null, null, null, null, null, null, null);
    }

    // ---------------------------------------------------------------- the fix

    @Test
    void emptyWatchlistStillScreensTheIndexUniverse() {
        indexReturns("ACME", "HIGHFLYER");
        nearLow("ACME");
        farFromLow("HIGHFLYER");
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        assertThat(result.items()).extracting(c -> ((EnrichedLazarusCandidate) c).symbol())
                .containsExactly("ACME");
        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.health().partial()).isFalse();
        // the far-from-low name never costs a Finnhub-routed fundamentals call
        verify(companyData, never()).fundamentals("HIGHFLYER");
    }

    @Test
    void emptyUniverseIsUnavailableNotHealthy() {
        when(index.constituents("sp500"))
                .thenReturn(DataSourceResult.unavailable("agora", "agora: index sp500 returned no constituents"));

        var result = controller.hunt(Map.of());

        assertThat(result.health().isHealthy()).isFalse();
        assertThat(result.health().detail()).contains("sp500");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void watchlistOnlyUniverseIsPartialWhenTheIndexIsDown() {
        when(index.constituents("sp500"))
                .thenReturn(DataSourceResult.unavailable("agora", "agora: wikipedia unreachable"));
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("ACME", 10.50)));
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        assertThat(result.health().isHealthy()).isTrue();     // the watchlist result is still usable
        assertThat(result.health().partial()).isTrue();
        assertThat(result.health().detail()).contains("index");
        assertThat(result.items()).hasSize(1);
    }

    /** Watchlist names are screened UNCONDITIONALLY — they never pass through the cheap
     *  pre-filter, because the user tracking them by hand is the stronger signal. */
    @Test
    void watchlistNamesBypassThePreFilter() {
        indexReturns("OTHER");
        farFromLow("OTHER");
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("ACME", 10.50)));
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        verify(priceRange, never()).range52w("ACME");
        verify(companyData).fundamentals("ACME");
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void depotPositionsAreExcludedFromTheIndexUniverseToo() {
        indexReturns("ACME", "OWNED");
        nearLow("ACME");
        nearLow("OWNED");
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of(held("OWNED")));
        when(companyData.fundamentals(anyString())).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        verify(priceRange, never()).range52w("OWNED");
        assertThat(result.items()).extracting(c -> ((EnrichedLazarusCandidate) c).symbol())
                .containsExactly("ACME");
    }

    // ---------------------------------------------------------------- honest health

    @Test
    void perSymbolFundamentalsFailuresAreReportedAsPartial() {
        indexReturns("ACME", "NOFUND");
        nearLow("ACME");
        nearLow("NOFUND");
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());
        when(companyData.fundamentals("NOFUND")).thenReturn(null);

        var result = controller.hunt(Map.of());

        assertThat(result.items()).hasSize(1);                 // what we got is kept
        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.health().partial()).isTrue();
        assertThat(result.health().detail()).contains("fundamentals");
    }

    @Test
    void missing52WeekLowIsReportedAsPartial() {
        indexReturns("ACME", "NO52W");
        nearLow("ACME");
        nearLow("NO52W");
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());
        when(companyData.fundamentals("NO52W")).thenReturn(MAPPER.readTree(
                "{\"roaTTM\":5.0,\"pbAnnual\":1.2,\"freeCashFlowPerShareTTM\":2.3}"));

        var result = controller.hunt(Map.of());

        assertThat(result.health().partial()).isTrue();
        assertThat(result.health().detail()).contains("52-week low");
    }

    /** A candidate that came back MISSING an enrichment source is still a candidate, so the
     *  size-based {@code enrichmentDropped} counter cannot see it. It reaches the health through
     *  {@code EnrichedLazarusBatch.degradedCandidates()} instead — as partial, never as a drop. */
    @Test
    void candidatesThatLostAnEnrichmentSourceAreReportedAsPartial() {
        indexReturns("ACME");
        nearLow("ACME");
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());
        // doAnswer, not when(...): re-stubbing with when() would CALL the mock and re-enter the
        // setUp answer with a null argument.
        doAnswer(i -> {
            List<LazarusCandidate> in = i.getArgument(0);
            return new EnrichedLazarusBatch(
                    in.stream().map(StrigoiLazarusMarketUniverseTest::enriched).toList(), 1);
        }).when(enrichment).enrich(any());

        var result = controller.hunt(Map.of());

        assertThat(result.items()).hasSize(1);                 // the candidate is kept
        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.health().partial()).isTrue();
        assertThat(result.health().truncated()).isFalse();
        assertThat(result.health().detail()).contains("lost at least one enrichment source");
    }

    @Test
    void preFilterFailuresAreReportedAsPartial() {
        indexReturns("ACME", "UNUSABLE");
        nearLow("ACME");
        when(priceRange.range52w("UNUSABLE")).thenReturn(RangeProbe.unusable());
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        assertThat(result.items()).hasSize(1);
        assertThat(result.health().partial()).isTrue();
        assertThat(result.health().detail()).contains("pre-filter");
    }

    /** The 2026-08-05 false positive: FDXF, HONA and Q are S&P 500 members younger than 52 weeks,
     *  so their range is not computable and never will be until they age. Losing them is not a
     *  degradation, and reporting it as one made the daily analysis alarm fire every night. */
    @Test
    void symbolsTooYoungForA52WeekRangeAreNotReportedAsPartial() {
        indexReturns("ACME", "TOOYOUNG");
        nearLow("ACME");
        when(priceRange.range52w("TOOYOUNG")).thenReturn(RangeProbe.notEligible());
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        assertThat(result.items()).hasSize(1);
        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.health().partial()).isFalse();
        assertThat(result.health().truncated()).isFalse();
    }

    @Test
    void aUniverseCappedByUniverseMaxIsReportedAsTruncated() {
        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, new LazarusScreener(), enrichment,
                mock(PreyRepository.class), new ToolFetchCache(new AgentToolCatalog(List.of()), 0),
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION, "",
                0.10, 3.0, 2.0, 20, "AAPL",
                "sp500", 1, 0.25, 150_000L, 10, 60);
        indexReturns("ACME", "SECOND");
        nearLow("ACME");
        nearLow("SECOND");
        when(companyData.fundamentals(anyString())).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        assertThat(result.health().truncated()).isTrue();
        assertThat(result.health().detail()).contains("universe");
    }

    @Test
    void aFundamentalsBudgetThatBitesIsReportedAsTruncated() {
        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, new LazarusScreener(), enrichment,
                mock(PreyRepository.class), new ToolFetchCache(new AgentToolCatalog(List.of()), 0),
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION, "",
                0.10, 3.0, 2.0, 20, "AAPL",
                "sp500", 600, 0.25, 150_000L, 10, 1);
        indexReturns("ACME", "SECOND");
        nearLow("ACME");
        nearLow("SECOND");
        when(companyData.fundamentals(anyString())).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        assertThat(result.health().truncated()).isTrue();
        assertThat(result.health().detail()).contains("fundamentals budget");
        assertThat(result.items()).hasSize(1);
    }

    /** The upfront canary still guards the expensive stage — but only once there is something
     *  to spend fundamentals calls on. */
    @Test
    void canaryUnavailableMakesTheWholeHuntUnavailable() {
        indexReturns("ACME");
        nearLow("ACME");
        when(companyData.fundamentalsResult("AAPL"))
                .thenReturn(DataSourceResult.unavailable("agora", "agora down"));

        var result = controller.hunt(Map.of());

        assertThat(result.health().isHealthy()).isFalse();
        verify(companyData, never()).fundamentals("ACME");
    }

    @Test
    void noShortlistMeansNoCanaryCall() {
        indexReturns("HIGHFLYER");
        farFromLow("HIGHFLYER");

        var result = controller.hunt(Map.of());

        assertThat(result.items()).isEmpty();
        assertThat(result.health().isHealthy()).isTrue();
        verify(companyData, never()).fundamentalsResult(anyString());
    }

    /** The universe source is configurable: "watchlist" restores the pre-D7 behaviour without
     *  a code change, for an operator who needs to fall back. */
    @Test
    void watchlistUniverseSourceSkipsTheIndexEntirely() {
        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, new LazarusScreener(), enrichment,
                mock(PreyRepository.class), new ToolFetchCache(new AgentToolCatalog(List.of()), 0),
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION, "",
                0.10, 3.0, 2.0, 20, "AAPL",
                "watchlist", 600, 0.25, 150_000L, 10, 60);
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("ACME", 10.50)));
        when(companyData.fundamentals("ACME")).thenReturn(goodFundamentals());

        var result = controller.hunt(Map.of());

        verify(index, never()).constituents(anyString());
        assertThat(result.items()).hasSize(1);
        assertThat(result.health().partial()).isFalse();
    }

    /** …and with that fallback selected AND an empty watchlist, the hunt is unavailable — the
     *  one thing it must never again be is "healthy, zero candidates". */
    @Test
    void watchlistUniverseSourceWithAnEmptyWatchlistIsUnavailable() {
        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, new LazarusScreener(), enrichment,
                mock(PreyRepository.class), new ToolFetchCache(new AgentToolCatalog(List.of()), 0),
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION, "",
                0.10, 3.0, 2.0, 20, "AAPL",
                "watchlist", 600, 0.25, 150_000L, 10, 60);

        var result = controller.hunt(Map.of());

        assertThat(result.health().isHealthy()).isFalse();
        assertThat(result.health().detail()).contains("universe");
    }
}
