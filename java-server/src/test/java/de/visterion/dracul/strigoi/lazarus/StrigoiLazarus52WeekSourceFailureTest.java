package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraIndexConstituents;
import de.visterion.dracul.hunting.agora.AgoraPriceRange;
import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-S29: a provider outage must not be counted as "this company has no 52-week low".
 *
 * <p>Both cases arrive as a null {@code 52WeekLow}. Since agora c89dba7 only the SOURCE failure
 * carries the group-scoped marker {@code "52WeekRange": {"available": false, "error": "..."}} —
 * an instrument-scoped absence deliberately carries none. The hunter counts them apart and only
 * the source failure reaches {@code partial}, mirroring the pre-filter's
 * {@code probeFailed} / {@code notEligible} split.
 *
 * <p>All payloads here are hand-written synthetic fixtures for the invented symbols
 * {@code SYNA} / {@code SYNB} — never exported from a running instance.
 */
class StrigoiLazarus52WeekSourceFailureTest {

    private static final String CONNECTION = "depot-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private WatchlistRepository watchlist;
    private HeldPositionService heldPositionService;
    private AgoraCompanyData companyData;
    private StrigoiLazarusWebhookController controller;

    @BeforeEach
    void setUp() {
        watchlist = mock(WatchlistRepository.class);
        heldPositionService = mock(HeldPositionService.class);
        companyData = mock(AgoraCompanyData.class);
        when(companyData.fundamentalsResult(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());

        var screener = new LazarusScreener();
        var enrichment = mock(LazarusEnrichmentService.class);
        when(enrichment.enrich(any())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        var index = mock(AgoraIndexConstituents.class);
        var priceRange = mock(AgoraPriceRange.class);
        var preyRepo = mock(PreyRepository.class);
        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);

        var listingResolver = new LazarusListingResolver(companyData, 40);
        var fx = mock(FxService.class);

        // universe-source=watchlist keeps the index/pre-filter out of the picture: the only losses
        // this suite may see are the ones the fundamentals stage produces.
        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, screener, enrichment, listingResolver, fx, preyRepo,
                cache, mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION,
                "default",
                0.10, 3.0, 2.0, 20, 100_000.0, "AAPL",
                "watchlist", 600, 0.25, 150_000L, 10, 60);
    }

    /** Fundamentals whose 52-week group failed at the SOURCE: no 52WeekLow, marker present. */
    private void fundamentalsWithFailureMarker(String symbol) {
        when(companyData.fundamentals(symbol)).thenReturn(mapper.readTree(
                "{\"roaTTM\":10.0,\"currentRatioQuarterly\":2.0,"
                        + "\"totalDebt/totalEquityQuarterly\":0.5,\"pbAnnual\":1.1,"
                        + "\"52WeekRange\":{\"available\":false,"
                        + "\"error\":\"synthetic provider outage\"}}"));
    }

    /** Fundamentals with a genuinely absent 52-week low: no 52WeekLow, no marker. */
    private void fundamentalsWithoutWeek52Low(String symbol) {
        when(companyData.fundamentals(symbol)).thenReturn(mapper.readTree(
                "{\"roaTTM\":10.0,\"currentRatioQuarterly\":2.0,"
                        + "\"totalDebt/totalEquityQuarterly\":0.5,\"pbAnnual\":1.1}"));
    }

    private WatchlistItem item(String ticker) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Inc",
                10.0, 0.0, "ACTIVE", "2025-01-01T00:00:00Z", "WATCHED",
                null, List.of(), List.of(), null, null, "default", "USD", "USD");
    }

    @Test
    void aFailedSourceIsADegradationAndReportsPartial() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("SYNA")));
        fundamentalsWithFailureMarker("SYNA");

        var result = controller.hunt(Map.of());

        assertThat(result.items()).isEmpty();          // still no false candidate
        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.health().partial()).isTrue();
        assertThat(result.health().detail())
                .contains("52-week range source unavailable");
        assertThat(result.health().detail()).doesNotContain("no 52-week low");
    }

    @Test
    void aGenuinelyAbsentValueIsNotADegradation() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("SYNB")));
        fundamentalsWithoutWeek52Low("SYNB");

        var result = controller.hunt(Map.of());

        assertThat(result.items()).isEmpty();
        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.health().partial()).isFalse();
        assertThat(result.health().truncated()).isFalse();
        assertThat(result.health().detail()).isNull();
    }

    /**
     * Pre-deploy pin: agora c89dba7 is BUILT BUT NOT DEPLOYED, so today's payloads carry no
     * {@code 52WeekRange} key at all. Nothing may key on its presence in a way that changes
     * behaviour for such a payload — it must read exactly as an instrument-scoped absence.
     */
    @Test
    void todaysAgoraPayloadWithoutTheMarkerReadsAsAGenuineAbsence() {
        var metrics = mapper.readTree(
                "{\"roaTTM\":10.0,\"grossMarginTTM\":30.0,\"netProfitMarginTTM\":9.0,"
                        + "\"currentRatioQuarterly\":2.0,\"totalDebt/totalEquityQuarterly\":0.5}");
        BasicFinancials f = BasicFinancialsExtractor.extract(metrics);

        assertThat(f.week52Low()).isNull();
        assertThat(f.week52RangeUnavailable()).isFalse();

        // …and a full, healthy payload is untouched as well: the two 52-week keys keep their meaning.
        BasicFinancials healthy = BasicFinancialsExtractor.extract(mapper.readTree(
                "{\"52WeekLow\":10.0,\"52WeekHigh\":40.0,\"roaTTM\":10.0}"));
        assertThat(healthy.week52Low()).isEqualTo(10.0);
        assertThat(healthy.week52High()).isEqualTo(40.0);
        assertThat(healthy.week52RangeUnavailable()).isFalse();
    }

    /** The marker is read defensively: only an explicit {@code available:false} means failure. */
    @Test
    void onlyAnExplicitAvailableFalseCountsAsAFailure() {
        assertThat(BasicFinancialsExtractor.extract(mapper.readTree(
                "{\"52WeekRange\":{\"available\":true}}")).week52RangeUnavailable()).isFalse();
        assertThat(BasicFinancialsExtractor.extract(mapper.readTree(
                "{\"52WeekRange\":\"unavailable\"}")).week52RangeUnavailable()).isFalse();
        assertThat(BasicFinancialsExtractor.extract(mapper.readTree(
                "{\"52WeekRange\":{\"available\":false,\"error\":\"x\"}}"))
                .week52RangeUnavailable()).isTrue();
    }
}
