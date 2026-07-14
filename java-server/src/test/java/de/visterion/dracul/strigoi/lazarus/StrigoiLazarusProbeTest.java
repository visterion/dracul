package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Reachability-probe fix (B4): the upfront Agora-health probe must hit a fixed, configurable US
 * canary ({@code dracul.strigoi.lazarus.probe-symbol}, default AAPL), NOT {@code items.get(0)}.
 * With the watchlist ordered {@code added_at DESC}, a freshly-seeded non-US row is
 * {@code items.get(0)}; probing it while the global-metrics flag is OFF would wrongly declare the
 * whole hunt unavailable and kill the healthy US flow.
 */
class StrigoiLazarusProbeTest {

    private static final String CONNECTION = "depot-1";
    private static final String CANARY = "AAPL";

    private WatchlistRepository watchlist;
    private HeldPositionService heldPositionService;
    private AgoraCompanyData companyData;
    private StrigoiLazarusWebhookController controller;

    @BeforeEach
    void setUp() {
        watchlist = mock(WatchlistRepository.class);
        heldPositionService = mock(HeldPositionService.class);
        companyData = mock(AgoraCompanyData.class);
        var screener = new LazarusScreener();
        var enrichment = mock(LazarusEnrichmentService.class);
        when(enrichment.enrich(any())).thenReturn(List.of());
        var preyRepo = mock(PreyRepository.class);
        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);

        when(companyData.fundamentals(anyString())).thenReturn(null);
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());

        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, screener, enrichment, preyRepo, cache,
                heldPositionService, CONNECTION,
                0.10, 3.0, 2.0, 20, CANARY);
    }

    private WatchlistItem item(String ticker, String currency) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Inc",
                10.0, 0.0, "ACTIVE", "2025-01-01T00:00:00Z", "WATCHED",
                null, List.of(), List.of(), null, null, "default", currency, currency);
    }

    @Test
    void nonUsFirstItem_healthyCanary_huntProceeds() {
        // items.get(0) is a non-US row whose fundamentals are unavailable; the canary (AAPL) is healthy.
        when(watchlist.findAllByUser("default"))
                .thenReturn(List.of(item("SAP.DE", "EUR"), item("MSFT", "USD")));
        when(companyData.fundamentalsResult(CANARY))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));

        var result = controller.hunt(Map.of());

        assertThat(result.health().isHealthy()).isTrue();
        // The canary is probed; the non-US items.get(0) is NEVER probed.
        verify(companyData).fundamentalsResult(CANARY);
        verify(companyData, never()).fundamentalsResult("SAP.DE");
    }

    @Test
    void emptyWatchlist_noProbe() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of());

        var result = controller.hunt(Map.of());

        assertThat(result.health().isHealthy()).isTrue();
        verify(companyData, never()).fundamentalsResult(anyString());
    }

    @Test
    void canaryUnavailable_huntUnavailable() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("SAP.DE", "EUR")));
        when(companyData.fundamentalsResult(CANARY))
                .thenReturn(DataSourceResult.unavailable("agora", "agora down"));

        var result = controller.hunt(Map.of());

        assertThat(result.health().isHealthy()).isFalse();
        verify(companyData).fundamentalsResult(CANARY);
    }
}
