package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraIndexConstituents;
import de.visterion.dracul.hunting.agora.AgoraPriceRange;
import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        var listingResolver = new LazarusListingResolver(companyData, 40);
        var fx = mock(FxService.class);
        return new StrigoiLazarusWebhookController(
                "tok", watchlist, companyData, screener, enrichment, listingResolver, fx, preyRepo,
                cache, mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                heldPositionService, index, new LazarusUniverseService(priceRange), CONNECTION,
                primaryUser,
                0.10, 3.0, 2.0, 20, 100_000.0, "AAPL",
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

    // ================================================================================
    // Task 4: USD normalisation, the size decision and the three counters. The screener and
    // the listing resolver are mocked here (unlike the suite above) so each test can hand the
    // controller an exact LazarusCandidate — with a chosen marketCap/currency/cheapGatePassed/
    // listingResolution — without having to route synthetic fundamentals through the real
    // screener and a real Agora profile lookup. `enrichment` is captured so the assertions read
    // straight off the LazarusCandidate the controller actually decided to keep, which is where
    // marketCapUsdMillions/marketCapAvailable end up (EnrichedLazarusCandidate is mocked away).
    // ================================================================================

    /** One watchlist row is enough to reach {@code screener.screen(...)} at all; the screener
     *  itself is mocked, so its content is otherwise irrelevant to these tests. */
    private static WatchlistItem sizeWatchlistItem() {
        return new WatchlistItem("id-ACME", "ACME", "ACME Inc",
                10.0, 0.0, "ACTIVE", "2025-01-01T00:00:00Z", "WATCHED",
                null, List.of(), List.of(), null, null, "default", "USD", "USD");
    }

    private static LazarusCandidate rawCandidate(String symbol, Double marketCap, String currency,
            boolean cheapGatePassed, ListingResolution listing) {
        return new LazarusCandidate(symbol, symbol + " Inc", 10.0, 9.0, 40.0, 0.05,
                5.0, 1.8, 0.4, 35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, marketCap, currency,
                cheapGatePassed, listing, null, false);
    }

    /** Builds a controller wired with mocked {@code screener}/{@code listingResolver}/{@code fx}
     *  so each test controls the exact candidate(s) and {@link LazarusListingResolver.Resolved}
     *  the controller has to decide on. {@code enrichment} is left to the caller to stub/capture. */
    private StrigoiLazarusWebhookController sizeDecisionController(
            LazarusScreener screener, LazarusListingResolver listingResolver, FxService fx,
            LazarusEnrichmentService enrichment, double megaCapUsdMillions) {
        var wl = mock(WatchlistRepository.class);
        var held = mock(HeldPositionService.class);
        var data = mock(AgoraCompanyData.class);
        var index = mock(AgoraIndexConstituents.class);
        var priceRange = mock(AgoraPriceRange.class);
        var preyRepo = mock(PreyRepository.class);
        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);

        when(wl.findAllByUser("default")).thenReturn(List.of(sizeWatchlistItem()));
        when(held.openPositions(CONNECTION)).thenReturn(List.of());
        when(data.fundamentalsResult(anyString()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        // A minimal, VALID fundamentals payload for the one watchlist row -- so this suite's own
        // health flags (fundamentalsMissing etc.) never fire and pollute the assertions below.
        // Its content is otherwise irrelevant: the screener is mocked and ignores its `raws`
        // argument, always returning the canned ScreenResult the test stubbed in.
        when(data.fundamentals(anyString())).thenReturn(
                new tools.jackson.databind.ObjectMapper().readTree(
                        "{\"52WeekLow\":9.0,\"52WeekHigh\":40.0,\"roaTTM\":5.0}"));

        return new StrigoiLazarusWebhookController(
                "tok", wl, data, screener, enrichment, listingResolver, fx, preyRepo, cache,
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                held, index, new LazarusUniverseService(priceRange), CONNECTION, "",
                0.10, 3.0, 2.0, 20, megaCapUsdMillions, "AAPL",
                "watchlist", 600, 0.25, 150_000L, 10, 60);
    }

    private LazarusScreener screenerReturning(LazarusCandidate... candidates) {
        var screener = mock(LazarusScreener.class);
        when(screener.screen(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new ScreenResult(List.of(candidates), 0));
        return screener;
    }

    private LazarusListingResolver resolverReturning(int foreignListing, int listingUnknown,
            LazarusCandidate... resolved) {
        var resolver = mock(LazarusListingResolver.class);
        when(resolver.resolve(any())).thenReturn(new LazarusListingResolver.Resolved(
                List.of(resolved), foreignListing, listingUnknown));
        return resolver;
    }

    /** Captures whatever the controller decided to hand to {@code enrichment.enrich(...)} —
     *  i.e. the list AFTER the size decision — as an {@link EnrichedLazarusBatch} the test can
     *  then assert on directly (marketCapUsdMillions/marketCapAvailable already live on
     *  {@link LazarusCandidate}, so no fake enriched wire shape is needed). */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<LazarusCandidate>> captureScreened(LazarusEnrichmentService enrichment) {
        ArgumentCaptor<List<LazarusCandidate>> captor = ArgumentCaptor.forClass(List.class);
        when(enrichment.enrich(captor.capture())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        return captor;
    }

    @Test
    void unknownListingGetsNoSizeExemption() {
        LazarusCandidate c = rawCandidate("UNK", 9_000_000.0, null, false, ListingResolution.UNKNOWN);
        var enrichment = mock(LazarusEnrichmentService.class);
        var captor = captureScreened(enrichment);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 1, c.withListing(ListingResolution.UNKNOWN)),
                mock(FxService.class), enrichment, 100_000.0);

        controller.hunt(Map.of());

        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void usConfirmedAboveThresholdKeepsExemption() {
        LazarusCandidate c = rawCandidate("BIG", 250_000.0, null, false, ListingResolution.US_CONFIRMED);
        var enrichment = mock(LazarusEnrichmentService.class);
        var captor = captureScreened(enrichment);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), mock(FxService.class), enrichment, 100_000.0);

        controller.hunt(Map.of());

        assertThat(captor.getValue()).hasSize(1);
        LazarusCandidate kept = captor.getValue().get(0);
        assertThat(kept.marketCapUsdMillions()).isEqualTo(250_000.0);
        assertThat(kept.marketCapAvailable()).isTrue();
    }

    /** Defensive-in-depth: {@link LazarusListingResolver} only ever assigns US_CONFIRMED with a
     *  null/blank reportingCurrency today, so this cannot happen through the real resolver — but
     *  the invariant lives in a different class. Should a future resolver change ever pair
     *  US_CONFIRMED with a set currency, the controller must still refuse to read that raw figure
     *  as USD rather than silently reading e.g. EUR millions past the mega-cap threshold. */
    @Test
    void usConfirmedWithASetCurrencyIsNeverTrustedAsUsd() {
        LazarusCandidate c = rawCandidate("ODD", 250_000.0, "EUR", false, ListingResolution.US_CONFIRMED);
        var enrichment = mock(LazarusEnrichmentService.class);
        var captor = captureScreened(enrichment);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), mock(FxService.class), enrichment, 100_000.0);

        controller.hunt(Map.of());

        assertThat(captor.getValue()).isEmpty(); // not cheap, and no trusted USD size either
    }

    @Test
    void usConfirmedBelowThresholdIsDroppedSilently() {
        LazarusCandidate c = rawCandidate("SMALL", 60_000.0, null, false, ListingResolution.US_CONFIRMED);
        var enrichment = mock(LazarusEnrichmentService.class);
        var captor = captureScreened(enrichment);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), mock(FxService.class), enrichment, 100_000.0);

        var result = controller.hunt(Map.of());

        assertThat(captor.getValue()).isEmpty();
        assertThat(result.health().partial()).isFalse();
        assertThat(result.health().truncated()).isFalse();
        assertThat(result.health().detail()).isNull();
    }

    @Test
    void foreignSuffixedIsConvertedBeforeTheThreshold() {
        LazarusCandidate below = rawCandidate("XTSA", 200_000.0, "XTS", false, ListingResolution.FOREIGN_SUFFIXED);
        var enrichmentBelow = mock(LazarusEnrichmentService.class);
        var captorBelow = captureScreened(enrichmentBelow);
        var fxBelow = mock(FxService.class);
        when(fxBelow.hasRate("XTS", "USD")).thenReturn(true);
        when(fxBelow.convert(eq(BigDecimal.valueOf(200_000.0)), eq("XTS"), eq("USD")))
                .thenReturn(BigDecimal.valueOf(80_000.0)); // 200_000 * 0.4
        sizeDecisionController(screenerReturning(below), resolverReturning(0, 0, below),
                fxBelow, enrichmentBelow, 100_000.0).hunt(Map.of());

        assertThat(captorBelow.getValue()).isEmpty(); // 80_000 USD < 100_000 threshold

        LazarusCandidate above = rawCandidate("XTSB", 200_000.0, "XTS", false, ListingResolution.FOREIGN_SUFFIXED);
        var enrichmentAbove = mock(LazarusEnrichmentService.class);
        var captorAbove = captureScreened(enrichmentAbove);
        var fxAbove = mock(FxService.class);
        when(fxAbove.hasRate("XTS", "USD")).thenReturn(true);
        when(fxAbove.convert(eq(BigDecimal.valueOf(200_000.0)), eq("XTS"), eq("USD")))
                .thenReturn(BigDecimal.valueOf(160_000.0)); // 200_000 * 0.8
        sizeDecisionController(screenerReturning(above), resolverReturning(0, 0, above),
                fxAbove, enrichmentAbove, 100_000.0).hunt(Map.of());

        assertThat(captorAbove.getValue()).hasSize(1); // 160_000 USD >= 100_000 threshold
        assertThat(captorAbove.getValue().get(0).marketCapUsdMillions()).isEqualTo(160_000.0);
    }

    @Test
    void missingFxRateFailsClosed() {
        // cheapGatePassed=true so the candidate survives regardless of size, letting the test
        // inspect the marketCapUsdMillions/marketCapAvailable fields the controller computed.
        LazarusCandidate c = rawCandidate("CNYX", 1_500_000.0, "CNY", true, ListingResolution.FOREIGN_SUFFIXED);
        var enrichment = mock(LazarusEnrichmentService.class);
        var captor = captureScreened(enrichment);
        var fx = mock(FxService.class);
        when(fx.hasRate("CNY", "USD")).thenReturn(false);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), fx, enrichment, 100_000.0);

        controller.hunt(Map.of());

        assertThat(captor.getValue()).hasSize(1);
        LazarusCandidate kept = captor.getValue().get(0);
        assertThat(kept.marketCapUsdMillions()).isNull();
        assertThat(kept.marketCapAvailable()).isFalse();
        verify(fx, never()).convert(any(), any(), any());
    }

    @Test
    void cheapCandidateSurvivesWithoutAnySize() {
        LazarusCandidate c = rawCandidate("CHEAP", null, null, true, ListingResolution.UNKNOWN);
        var enrichment = mock(LazarusEnrichmentService.class);
        var captor = captureScreened(enrichment);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), mock(FxService.class), enrichment, 100_000.0);

        controller.hunt(Map.of());

        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).marketCapAvailable()).isFalse();
    }

    @Test
    void fxIsWarmedOncePerCurrency() {
        LazarusCandidate[] twenty = new LazarusCandidate[20];
        for (int i = 0; i < 20; i++) {
            twenty[i] = rawCandidate("SYM" + i, 200_000.0, "XTS", true, ListingResolution.FOREIGN_SUFFIXED);
        }
        var enrichment = mock(LazarusEnrichmentService.class);
        captureScreened(enrichment);
        var fx = mock(FxService.class);
        when(fx.hasRate("XTS", "USD")).thenReturn(true);
        when(fx.convert(any(), eq("XTS"), eq("USD"))).thenReturn(BigDecimal.valueOf(80_000.0));
        var controller = sizeDecisionController(screenerReturning(twenty),
                resolverReturning(0, 0, twenty), fx, enrichment, 100_000.0);

        controller.hunt(Map.of());

        verify(fx, times(1)).warm("XTS", "USD");
    }

    @Test
    void noNonUsdCurrencyMeansNoWarmCall() {
        LazarusCandidate c = rawCandidate("USONLY", 250_000.0, null, false, ListingResolution.US_CONFIRMED);
        var enrichment = mock(LazarusEnrichmentService.class);
        captureScreened(enrichment);
        var fx = mock(FxService.class);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), fx, enrichment, 100_000.0);

        controller.hunt(Map.of());

        verify(fx, never()).warm(any(), any());
    }

    /** A blank (non-null) currency carries no evidence, mirroring
     *  {@code LazarusListingResolver}'s own rule (LazarusListingResolver.java:78) — it is not USD,
     *  but it is also not a real ISO code to warm a rate for. Without the {@code isBlank()} guard
     *  this used to fire a pointless {@code get_fx_rate(from="")} call once per night. */
    @Test
    void blankCurrencyNeverWarmsEither() {
        LazarusCandidate c = rawCandidate("BLANK", 250_000.0, "  ", true, ListingResolution.FOREIGN_SUFFIXED);
        var enrichment = mock(LazarusEnrichmentService.class);
        captureScreened(enrichment);
        var fx = mock(FxService.class);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), fx, enrichment, 100_000.0);

        controller.hunt(Map.of());

        verify(fx, never()).warm(any(), any());
    }

    @Test
    void enrichmentDroppedIgnoresSizeHopes() {
        // Run A: no size hopes at all -- the screener/resolver return nothing.
        var enrichmentA = mock(LazarusEnrichmentService.class);
        when(enrichmentA.enrich(any())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        var resultA = sizeDecisionController(screenerReturning(), resolverReturning(0, 0),
                mock(FxService.class), enrichmentA, 100_000.0).hunt(Map.of());

        // Run B: ten size hopes, all correctly filtered out below the threshold.
        LazarusCandidate[] ten = new LazarusCandidate[10];
        for (int i = 0; i < 10; i++) {
            ten[i] = rawCandidate("HOPE" + i, 1_000.0, null, false, ListingResolution.US_CONFIRMED);
        }
        var enrichmentB = mock(LazarusEnrichmentService.class);
        when(enrichmentB.enrich(any())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        var resultB = sizeDecisionController(screenerReturning(ten), resolverReturning(0, 0, ten),
                mock(FxService.class), enrichmentB, 100_000.0).hunt(Map.of());

        assertThat(resultB.health().detail()).isEqualTo(resultA.health().detail());
        assertThat(resultB.health().partial()).isFalse();
        assertThat(Optional.ofNullable(resultB.health().detail()).orElse(""))
                .doesNotContain("dropped during enrichment");
    }

    @Test
    void listingUnknownRaisesPartialWithBothConsequences() {
        var enrichment = mock(LazarusEnrichmentService.class);
        when(enrichment.enrich(any())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        var controller = sizeDecisionController(screenerReturning(), resolverReturning(0, 3),
                mock(FxService.class), enrichment, 100_000.0);

        var result = controller.hunt(Map.of());

        assertThat(result.health().partial()).isTrue();
        assertThat(result.health().detail()).contains("size exemption");
        assertThat(result.health().detail()).contains("Altman-Z");
    }

    @Test
    void foreignListingRaisesNoFlag() {
        var enrichment = mock(LazarusEnrichmentService.class);
        when(enrichment.enrich(any())).thenReturn(new EnrichedLazarusBatch(List.of(), 0));
        var controller = sizeDecisionController(screenerReturning(), resolverReturning(2, 0),
                mock(FxService.class), enrichment, 100_000.0);

        var result = controller.hunt(Map.of());

        assertThat(result.health().partial()).isFalse();
        assertThat(result.health().truncated()).isFalse();
    }

    /** Geerbter Regressionsanker aus Task 2: {@code megaCapUsdMillions = 0} muss die Ausnahme
     *  abschalten, nicht jedem bepreisten Kandidaten die Ausnahme geben. */
    @Test
    void zeroThresholdDisablesTheExemption() {
        LazarusCandidate c = rawCandidate("ZERO", 500_000.0, null, false, ListingResolution.US_CONFIRMED);
        var enrichment = mock(LazarusEnrichmentService.class);
        var captor = captureScreened(enrichment);
        var controller = sizeDecisionController(screenerReturning(c),
                resolverReturning(0, 0, c), mock(FxService.class), enrichment, 0.0);

        controller.hunt(Map.of());

        assertThat(captor.getValue()).isEmpty();
    }
}
