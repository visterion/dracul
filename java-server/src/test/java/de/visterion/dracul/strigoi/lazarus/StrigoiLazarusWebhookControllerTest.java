package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraIndexConstituents;
import de.visterion.dracul.hunting.agora.AgoraPriceRange;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
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
 *  something already owned as a "new" quality-at-low candidate makes no sense.
 *
 *  <p>Pinned on the {@code universe-source=watchlist} fallback so the dedup is tested in
 *  isolation from the D7 market-wide index universe (which has its own suite,
 *  {@link StrigoiLazarusMarketUniverseTest}). */
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
        when(enrichment.enrich(any())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        var index = mock(AgoraIndexConstituents.class);
        var priceRange = mock(AgoraPriceRange.class);
        var preyRepo = mock(PreyRepository.class);
        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);

        when(companyData.fundamentals(anyString())).thenReturn(null);
        when(companyData.fundamentalsResult(anyString()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));

        controller = newController("");
    }

    private StrigoiLazarusWebhookController newController(String primaryUser) {
        var screener = new LazarusScreener();
        var enrichment = mock(LazarusEnrichmentService.class);
        when(enrichment.enrich(any())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        var index = mock(AgoraIndexConstituents.class);
        var priceRange = mock(AgoraPriceRange.class);
        var preyRepo = mock(PreyRepository.class);
        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);
        return new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, screener, enrichment, preyRepo, cache,
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION,
                primaryUser,
                0.10, 3.0, 2.0, 20, "AAPL",
                "watchlist", 600, 0.25, 150_000L, 10, 60);
    }

    /**
     * Lazarus screened ZERO watchlist names in production, silently.
     *
     * <p>The owner was hard-coded to {@code "default"}, but {@code LegacyWatchlistOwnerMigration}
     * runs {@code UPDATE watchlist_items SET user_id = :email WHERE user_id = 'default'} on every
     * boot. Production {@code watchlist_items} holds 52 rows — 41 under the owner's account, 11
     * under a second — and <b>zero</b> under {@code default}. Every sibling (Renfield, gropar,
     * daywalker, stopguard) already reads the configurable {@code dracul.primary-user-email};
     * lazarus was never migrated. So D7's guarantee that "watchlist names are ALWAYS screened"
     * screened nothing, and the documented {@code universe-source: watchlist} fallback was a
     * fallback to an empty list.
     */
    @Test
    void screensTheWatchlistOfTheConfiguredPrimaryUser() {
        var configured = newController("owner@example.com");
        when(watchlist.findAllByUser("owner@example.com")).thenReturn(List.of(item("AAA")));
        when(watchlist.findAllByUser("default")).thenReturn(List.of());
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());

        configured.hunt(Map.of());

        verify(watchlist).findAllByUser("owner@example.com");
        verify(watchlist, never()).findAllByUser("default");
        verify(companyData).fundamentals("AAA");
    }

    /** An unset property keeps the pre-migration owner, exactly as every sibling does. */
    @Test
    void anUnsetPrimaryUserFallsBackToDefault() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of(item("AAA")));
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());

        newController("  ").hunt(Map.of());

        verify(watchlist).findAllByUser("default");
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
                java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, null,
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

    /** D7: with the index universe switched off AND an empty watchlist there is nothing to
     *  screen — and "nothing to screen" is an outage, not a quiet market. Reporting it as
     *  healthy-with-zero-candidates is precisely the production bug this suite now pins. */
    @Test
    void emptyUniverseIsUnavailableAndCostsNoFundamentalsCall() {
        when(watchlist.findAllByUser("default")).thenReturn(List.of());
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of());

        var result = controller.hunt(Map.of());

        assertThat(result.items()).isEmpty();
        assertThat(result.health().isHealthy()).isFalse();
        verify(companyData, never()).fundamentals(anyString());
    }
}
