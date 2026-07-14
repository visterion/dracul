package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.position.HeldPosition;
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

/** Unit tests for the lazarus dedup: a watchlist name whose symbol is already an open
 *  depot position is excluded from the candidate universe before screening — resurfacing
 *  something already owned as a "new" quality-at-low candidate makes no sense. */
class StrigoiLazarusWebhookControllerTest {

    private static final String CONNECTION = "depot-1";

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
        when(companyData.fundamentalsResult(anyString()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));

        controller = new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, screener, enrichment, preyRepo, cache,
                heldPositionService, CONNECTION,
                0.10, 3.0, 2.0, 20, "AAPL");
    }

    /** Real record, not a mock -- WatchlistItem's accessors are final (record), so mocking them
     *  raises UnfinishedStubbingException. */
    private WatchlistItem item(String ticker) {
        return new WatchlistItem("id-" + ticker, ticker, ticker + " Inc",
                10.0, 0.0, "ACTIVE", "2025-01-01T00:00:00Z", "WATCHED",
                null, List.of(), List.of(), null, null, "default", "USD", "USD");
    }

    private HeldPosition held(String symbol) {
        return new HeldPosition(symbol, java.math.BigDecimal.ONE, java.math.BigDecimal.TEN,
                java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void excludesSymbolAlreadyHeldInDepot() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("HELD1"), item("FREE1")));
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of(held("HELD1")));

        controller.hunt(Map.of());

        // Only the non-held symbol's fundamentals are ever fetched -- HELD1 never reaches the screen.
        verify(companyData, never()).fundamentals("HELD1");
        verify(companyData).fundamentals("FREE1");
    }

    @Test
    void depotDownExcludesNothing() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("AAA")));
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());

        controller.hunt(Map.of());

        verify(companyData).fundamentals("AAA");
    }

    @Test
    void noHeldPositionsPassesAllCandidatesThrough() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of());
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());

        var result = controller.hunt(Map.of());

        assertThat(result.items()).isEmpty();
        verify(companyData, never()).fundamentals(anyString());
    }
}
