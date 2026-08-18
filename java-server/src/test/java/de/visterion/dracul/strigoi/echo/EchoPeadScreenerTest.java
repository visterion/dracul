package de.visterion.dracul.strigoi.echo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.StubMarketDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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

    /** Mirrors the {@code ListAppender} idiom used across this branch's other outage-visibility
     *  tests: capture WARN/DEBUG output from {@link EchoPeadScreener} while {@code action} runs. */
    private static List<ILoggingEvent> logsWhile(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(EchoPeadScreener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
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
        var result = screener.screen(List.of(ev("GHOST", 1.00, 1.20, 20.0)));
        assertThat(result.candidates()).isEmpty();
        // an unregistered symbol is StubMarketDataPort's NOT_FOUND, a per-symbol miss — not a
        // source outage, so it must NOT trip the price-source-down flag.
        assertThat(result.priceSourceUnavailable()).isFalse();
    }

    /** The AUSFALL path this task exists to fix: before, a price-resolution outage dropped every
     *  remaining candidate silently and {@link ScreenResult} still reported an empty list with
     *  {@code truncated=false} — indistinguishable from a quiet night with no qualifying beats.
     *  Now the outage stops the screen early and is reported via {@code priceSourceUnavailable}. */
    @Test void reportsPriceSourceDownInsteadOfAnEmptyCleanList() {
        marketData.forceUnavailable();
        var result = screener.screen(List.of(ev("AAA", 1.00, 1.20, 20.0), ev("BBB", 1.00, 1.30, 15.0)));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.priceSourceUnavailable()).isTrue();
        assertThat(result.truncated()).isFalse();
    }

    @Test void stopsResolvingFurtherSymbolsOncePriceSourceIsDown() {
        marketData.register("AAA", "AAA Inc.", 50.0); // never queried: the outage below hits BBB first
        var counting = new StubMarketDataPort() {
            private boolean failed = false;
            @Override public MarketData resolve(String symbol) {
                if ("BBB".equals(symbol)) {
                    failed = true;
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "outage");
                }
                if (failed) throw new AssertionError("resolve() called for " + symbol + " after the source went down");
                return super.resolve(symbol);
            }
        };
        counting.register("CCC", "CCC Inc.", 50.0);
        var svc = new EchoPeadScreener(counting, new BigDecimal("5.0"), new BigDecimal("5.0"), MAX_CANDIDATES);

        // BBB has the strongest surprise and is resolved first (STRONGEST_FIRST); its outage must
        // stop CCC (a weaker, later candidate) from ever being queried.
        var result = svc.screen(List.of(
                ev("BBB", 1.00, 1.30, 20.0),
                ev("CCC", 1.00, 1.20, 10.0)));

        assertThat(result.priceSourceUnavailable()).isTrue();
        assertThat(result.candidates()).isEmpty();
    }

    @Test void logsAgoraSourceUnavailableOnPriceOutage() {
        marketData.forceUnavailable();

        List<ILoggingEvent> events = logsWhile(() ->
                screener.screen(List.of(ev("AAA", 1.00, 1.20, 20.0))));

        var warnings = events.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
                .isEqualTo("agora source unavailable: tool=get_quote subject=AAA — stub unavailable");
    }

    /** {@code AgoraMarketData.resolve} wraps EVERY {@code AgoraUnavailableException} in a {@code
     *  MarketDataException(UNAVAILABLE, ...)}, so the wrapper's Kind alone cannot tell a genuine
     *  outage apart from a single unresolvable symbol Agora answered about — the exact "one 404
     *  disabled a whole source" misjudgment {@code EnrichmentSourceGuard}'s javadoc names as the
     *  2026-08-06 incident. A REQUEST-scoped cause must therefore behave like a per-symbol miss:
     *  that one candidate is dropped, the source stays up, and the next (weaker) candidate is
     *  still resolved. */
    @Test void doesNotTripPriceSourceDownOnARequestScopedFailure() {
        var requestScoped = new StubMarketDataPort() {
            @Override public MarketData resolve(String symbol) {
                if ("BBB".equals(symbol)) {
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                            "Agora tool error: no such symbol",
                            new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST,
                                    "Agora tool error: no such symbol", null));
                }
                return super.resolve(symbol);
            }
        };
        requestScoped.register("CCC", "CCC Inc.", 50.0);
        var svc = new EchoPeadScreener(requestScoped, new BigDecimal("5.0"), new BigDecimal("5.0"), MAX_CANDIDATES);

        // BBB (strongest surprise) fails REQUEST-scoped; CCC (weaker, resolved next) must still
        // be tried and must still succeed.
        var result = svc.screen(List.of(
                ev("BBB", 1.00, 1.30, 20.0),
                ev("CCC", 1.00, 1.20, 10.0)));

        assertThat(result.priceSourceUnavailable()).isFalse();
        assertThat(result.candidates()).extracting(PeadCandidate::symbol).containsExactly("CCC");
    }

    /** The actual shape of the 2026-08-06 incident: Agora ANSWERED for every symbol, each time
     *  with a per-request error envelope (a Yahoo 404, an unresolvable issuer) — never a
     *  transport failure. A single-evidence guard (SOURCE-only) misses this entirely: every
     *  candidate is dropped one by one via {@code continue} and {@code priceSourceUnavailable}
     *  stays {@code false} — a screen that looks like a quiet night. The two-evidence guard
     *  (mirroring {@code EnrichmentSourceGuard}'s own SOURCE-immediate / REQUEST-run rule) must
     *  trip after the third consecutive REQUEST-scoped failure with no success in between, stop
     *  resolving the rest, and report the outage. */
    @Test void reportsOutageWhenEverySymbolAnswersWithARequestScopedEnvelope() {
        var resolves = new AtomicInteger();
        var alwaysRequestScoped = new StubMarketDataPort() {
            @Override public MarketData resolve(String symbol) {
                resolves.incrementAndGet();
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        "Agora tool error: no such symbol",
                        new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST,
                                "Agora tool error: no such symbol", null));
            }
        };
        var svc = new EchoPeadScreener(alwaysRequestScoped, new BigDecimal("5.0"), new BigDecimal("5.0"),
                MAX_CANDIDATES);
        List<EarningsObservation> events = List.of(
                ev("AAA", 1.00, 1.50, 50.0), ev("BBB", 1.00, 1.40, 40.0), ev("CCC", 1.00, 1.30, 30.0),
                ev("DDD", 1.00, 1.20, 20.0), ev("EEE", 1.00, 1.10, 10.0));

        var resultHolder = new ScreenResult[1];
        List<ILoggingEvent> logEvents = logsWhile(() -> resultHolder[0] = svc.screen(events));
        var result = resultHolder[0];

        assertThat(result.priceSourceUnavailable()).isTrue();
        assertThat(result.candidates()).isEmpty();
        // the guard trips after the 3rd consecutive REQUEST-scoped failure (mirrors
        // EnrichmentSourceGuard's own, non-public MAX_CONSECUTIVE_REQUEST_FAILURES = 3):
        // AAA, BBB, CCC are tried; DDD and EEE (weaker, later in STRONGEST_FIRST order) are not.
        assertThat(resolves.get()).isEqualTo(3);
        var warnings = logEvents.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
                .isEqualTo("agora source unavailable: tool=get_quote subject=CCC — Agora tool error: no such symbol");
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
                        IntStream.rangeClosed(10, MAX_CANDIDATES + 9)
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
        Collections.reverse(reversed);
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
        var resolves = new AtomicInteger();
        var counting = new StubMarketDataPort() {
            @Override public MarketData resolve(String symbol) {
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
