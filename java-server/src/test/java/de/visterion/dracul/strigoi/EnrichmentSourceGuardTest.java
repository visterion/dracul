package de.visterion.dracul.strigoi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentSourceGuardTest {

    /** Verbatim from the 2026-08-06 insider run — Yahoo saying it does not know THAT symbol. */
    private static final String YAHOO_404 =
            "Agora tool error: Yahoo Finance OHLC returned HTTP 404 NOT_FOUND";
    /** Verbatim from the same run — one issuer symbol that would not resolve to a CIK. */
    private static final String NO_CIK = "Agora tool error: no CIK for N/A";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(EnrichmentSourceGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    /** The shape production produces for an OHLC failure: AgoraMarketData re-wraps the Agora
     *  exception in a MarketDataException(UNAVAILABLE), keeping it as the cause. */
    private static MarketDataException wrappedOhlcFailure(String message) {
        var agora = new AgoraUnavailableException(
                AgoraUnavailableException.Scope.REQUEST, message, null);
        return new MarketDataException(MarketDataException.Kind.UNAVAILABLE, message, agora);
    }

    private static EnrichmentSourceGuard guard() {
        return EnrichmentSourceGuard.forSource("insider", "clusters", "ohlc history");
    }

    @Test
    void agoraUnavailableIsSourceDownAndWarns() {
        var g = EnrichmentSourceGuard.forSource("lazarus", "candidates", "ohlc");

        boolean down = g.recordFailure(new AgoraUnavailableException("agora offline"));

        assertThat(down).isTrue();
        assertThat(g.isDown()).isTrue();
        assertThat(appender.list).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage()).isEqualTo(
                    "lazarus enrichment: ohlc source down (agora offline), skipping it for the remaining candidates");
        });
    }

    @Test
    void marketDataUnavailableKindIsSourceDownAndWarns() {
        var g = EnrichmentSourceGuard.forSource("insider", "clusters", "recommendations");

        boolean down = g.recordFailure(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "feed down"));

        assertThat(down).isTrue();
        assertThat(appender.list).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage()).isEqualTo(
                    "insider enrichment: recommendations source down (feed down), skipping it for the remaining clusters");
        });
    }

    @Test
    void marketDataNotFoundKindIsNotSourceDownAndDoesNotWarn() {
        var g = EnrichmentSourceGuard.forSource("merger", "candidates", "ohlc history");

        boolean down = g.recordFailure(
                new MarketDataException(MarketDataException.Kind.NOT_FOUND, "symbol missing"));

        assertThat(down).isFalse();
        assertThat(appender.list).noneMatch(ev -> ev.getLevel() == Level.WARN);
    }

    @Test
    void unrelatedRuntimeExceptionIsNotSourceDownAndDoesNotWarn() {
        var g = EnrichmentSourceGuard.forSource("merger", "candidates", "ohlc history");

        boolean down = g.recordFailure(new IllegalStateException("boom"));

        assertThat(down).isFalse();
        assertThat(appender.list).noneMatch(ev -> ev.getLevel() == Level.WARN);
    }

    @Test
    void singleYahoo404DoesNotTripTheGuard() {
        var g = guard();

        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        assertThat(g.isDown()).isFalse();
        assertThat(appender.list).noneMatch(ev -> ev.getLevel() == Level.WARN);
    }

    @Test
    void singleMissingCikDoesNotTripTheGuard() {
        var g = EnrichmentSourceGuard.forSource("insider", "clusters", "form4 owner history");

        boolean down = g.recordFailure(new AgoraUnavailableException(
                AgoraUnavailableException.Scope.REQUEST, NO_CIK, null));

        assertThat(down).isFalse();
        assertThat(g.isDown()).isFalse();
        assertThat(appender.list).noneMatch(ev -> ev.getLevel() == Level.WARN);
    }

    @Test
    void aSuccessBetweenPerItemErrorsKeepsTheSourceUp() {
        var g = guard();

        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        g.recordSuccess();
        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();

        assertThat(g.isDown()).isFalse();
    }

    @Test
    void threeConsecutivePerItemErrorsTripTheGuard() {
        var g = guard();

        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isTrue();

        assertThat(g.isDown()).isTrue();
        assertThat(appender.list).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage()).isEqualTo(
                    "insider enrichment: ohlc history source down (3 consecutive per-item errors, last: "
                            + YAHOO_404 + "), skipping it for the remaining clusters");
        });
    }

    @Test
    void aNotFoundNeitherTripsNorResetsTheRun() {
        var g = guard();

        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        // benign per-item outcome in between: must not count, and must not clear the run either
        assertThat(g.recordFailure(
                new MarketDataException(MarketDataException.Kind.NOT_FOUND, "symbol missing"))).isFalse();
        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isFalse();
        assertThat(g.recordFailure(wrappedOhlcFailure(YAHOO_404))).isTrue();
    }
}
