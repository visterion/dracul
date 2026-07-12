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

    @Test
    void agoraUnavailableIsSourceDownAndWarns() {
        boolean down = EnrichmentSourceGuard.isSourceDown(
                new AgoraUnavailableException("agora offline"), "lazarus", "candidates", "ohlc");

        assertThat(down).isTrue();
        assertThat(appender.list).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage()).isEqualTo(
                    "lazarus enrichment: ohlc source down (agora offline), skipping it for the remaining candidates");
        });
    }

    @Test
    void marketDataUnavailableKindIsSourceDownAndWarns() {
        boolean down = EnrichmentSourceGuard.isSourceDown(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "feed down"),
                "insider", "clusters", "recommendations");

        assertThat(down).isTrue();
        assertThat(appender.list).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage()).isEqualTo(
                    "insider enrichment: recommendations source down (feed down), skipping it for the remaining clusters");
        });
    }

    @Test
    void marketDataNotFoundKindIsNotSourceDownAndDoesNotWarn() {
        boolean down = EnrichmentSourceGuard.isSourceDown(
                new MarketDataException(MarketDataException.Kind.NOT_FOUND, "symbol missing"),
                "merger", "candidates", "ohlc history");

        assertThat(down).isFalse();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void unrelatedRuntimeExceptionIsNotSourceDownAndDoesNotWarn() {
        boolean down = EnrichmentSourceGuard.isSourceDown(
                new IllegalStateException("boom"), "merger", "candidates", "ohlc history");

        assertThat(down).isFalse();
        assertThat(appender.list).isEmpty();
    }
}
