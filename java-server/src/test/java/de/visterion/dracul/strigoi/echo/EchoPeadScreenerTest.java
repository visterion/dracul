package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.StubMarketDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EchoPeadScreenerTest {

    /** The production default of {@code dracul.strigoi.echo.max-candidates}. Mirrored here so a
     *  change to the default has to be made deliberately in both places. */
    private static final int MAX_CANDIDATES = 40;

    private StubMarketDataPort marketData;
    private EchoPeadScreener screener;

    private static EarningsObservation ev(String sym, double estimate, double actual, double surprisePct) {
        return new EarningsObservation(sym, sym + " Inc.", LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(actual), BigDecimal.valueOf(estimate), BigDecimal.valueOf(surprisePct),
                BigDecimal.valueOf(1_000), BigDecimal.valueOf(900));
    }

    @BeforeEach
    void setUp() {
        marketData = new StubMarketDataPort();
        screener = new EchoPeadScreener(marketData, new BigDecimal("5.0"), new BigDecimal("5.0"),
                MAX_CANDIDATES);
    }

    private List<PeadCandidate> screen(List<EarningsObservation> events) {
        return screener.screen(events).candidates();
    }

    @Test
    void keepsPositiveSurpriseAboveThresholdAndCarriesRevenue() {
        marketData.register("AAPL", "Apple Inc.", 190.0);
        var out = screen(List.of(ev("AAPL", 1.50, 1.65, 10.0)));
        assertThat(out).hasSize(1);
        var c = out.get(0);
        assertThat(c.symbol()).isEqualTo("AAPL");
        assertThat(c.currentPrice()).isEqualByComparingTo("190.0");
        assertThat(c.revenueActual()).isEqualByComparingTo("1000");
    }

    @Test void dropsNegativeSurprise() {
        marketData.register("MISS", "Miss Co", 50.0);
        assertThat(screen(List.of(ev("MISS", 2.00, 1.80, -10.0)))).isEmpty();
    }
    @Test void dropsSurpriseBelowThreshold() {
        marketData.register("TINY", "Tiny Beat", 50.0);
        assertThat(screen(List.of(ev("TINY", 1.00, 1.02, 2.0)))).isEmpty();
    }
    @Test void dropsBelowMinPrice() {
        marketData.register("PENNY", "Penny Co", 2.50);
        assertThat(screen(List.of(ev("PENNY", 0.10, 0.20, 100.0)))).isEmpty();
    }
    @Test void dropsWhenPriceUnavailable() {
        assertThat(screen(List.of(ev("GHOST", 1.00, 1.20, 20.0)))).isEmpty();
    }
    @Test void dropsWhenEpsMissing() {
        marketData.register("NEW", "New Issue", 30.0);
        var noEps = new EarningsObservation("NEW", "New Issue", LocalDate.of(2026, 5, 20),
                null, null, BigDecimal.valueOf(50.0), null, null);
        assertThat(screen(List.of(noEps))).isEmpty();
    }

    /** Anchors the cap to the payload calibration of {@link EchoPayloadBudgetTest}: past ~56
     *  serialized candidates the Claude-Max bridge offloads the tool result into a file the agent
     *  cannot read, and echo silently returns empty prey (2026-07-22, 7 days). Since {@code
     *  AgoraEarnings} now asks for 1000 raw rows instead of the implicit 100, an uncapped screen
     *  would produce ~250-290 candidates. */
    @Test
    void capsAtMaxCandidatesAndKeepsTheStrongestSurprises() {
        List<EarningsObservation> events = new ArrayList<>();
        for (int i = 0; i < MAX_CANDIDATES + 10; i++) {
            String sym = String.format("SYM%02d", i);
            marketData.register(sym, sym + " Inc.", 100.0);
            events.add(ev(sym, 1.00, 1.50, 10.0 + i));   // surprise grows with i
        }

        var result = screener.screen(events);

        assertThat(result.truncated()).isTrue();
        assertThat(result.candidates()).hasSize(MAX_CANDIDATES);
        // The 40 STRONGEST survive (i = 49 down to i = 10), not the first 40 in input order.
        assertThat(result.candidates()).extracting(PeadCandidate::symbol)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(10, MAX_CANDIDATES + 9)
                                .map(i -> MAX_CANDIDATES + 19 - i)      // descending
                                .mapToObj(i -> String.format("SYM%02d", i))
                                .toList());
    }

    /** Ties are broken by symbol, so a capped run is reproducible: the same input always yields
     *  the same shortlist even when several candidates share one surprise value. */
    @Test
    void breaksSurpriseTiesBySymbolSoTheCapIsDeterministic() {
        var tied = new ArrayList<EarningsObservation>();
        for (int i = 0; i < MAX_CANDIDATES + 5; i++) {
            String sym = String.format("SYM%02d", i);
            marketData.register(sym, sym + " Inc.", 100.0);
            tied.add(ev(sym, 1.00, 1.50, 10.0));         // identical surprise for everyone
        }

        var first = screener.screen(tied);
        var reversed = new ArrayList<>(tied);
        java.util.Collections.reverse(reversed);
        var second = screener.screen(reversed);

        assertThat(first.candidates()).extracting(PeadCandidate::symbol)
                .isEqualTo(second.candidates().stream().map(PeadCandidate::symbol).toList());
        assertThat(first.candidates()).extracting(PeadCandidate::symbol)
                .first().isEqualTo("SYM00");            // symbol ascending on a tie
    }

    @Test
    void reportsNoTruncationWhenTheCapIsNotReached() {
        List<EarningsObservation> events = new ArrayList<>();
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            String sym = String.format("SYM%02d", i);
            marketData.register(sym, sym + " Inc.", 100.0);
            events.add(ev(sym, 1.00, 1.50, 10.0 + i));
        }

        var result = screener.screen(events);

        assertThat(result.truncated()).isFalse();
        assertThat(result.candidates()).hasSize(MAX_CANDIDATES);
    }

    /** The cap is applied BEFORE prices are resolved — {@code AgoraMarketData.resolve} makes two
     *  uncached Agora calls per symbol on a synchronized client, so resolving all 1000 rows just
     *  to throw most away would be paid for on every run. */
    @Test
    void resolvesPricesOnlyForTheShortlist() {
        var resolves = new java.util.concurrent.atomic.AtomicInteger();
        var counting = new StubMarketDataPort() {
            @Override public de.visterion.dracul.marketdata.MarketData resolve(String symbol) {
                resolves.incrementAndGet();
                return super.resolve(symbol);
            }
        };
        var capped = new EchoPeadScreener(counting, new BigDecimal("5.0"), new BigDecimal("5.0"),
                MAX_CANDIDATES);

        List<EarningsObservation> events = new ArrayList<>();
        for (int i = 0; i < MAX_CANDIDATES + 10; i++) {
            String sym = String.format("SYM%02d", i);
            counting.register(sym, sym + " Inc.", 100.0);
            events.add(ev(sym, 1.00, 1.50, 10.0 + i));
        }

        capped.screen(events);

        assertThat(resolves.get()).isEqualTo(MAX_CANDIDATES);
    }
}
