package de.visterion.dracul.strigoi.echo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SloanAccrualCalculatorTest {

    /** Captures WARN/INFO log output from {@link SloanAccrualCalculator} while {@code action}
     *  runs; mirrors the {@code ListAppender} idiom used across the other outage-visibility
     *  tests in this branch. */
    private static List<ILoggingEvent> warningsWhile(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(SloanAccrualCalculator.class);
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

    private static ConceptSeries.Point duration(String start, String end, String value) {
        return new ConceptSeries.Point(LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(value));
    }

    private static ConceptSeries.Point instant(String end, String value) {
        return new ConceptSeries.Point(null, LocalDate.parse(end), new BigDecimal(value));
    }

    private static AgoraFilings filings(ConceptSeries ni, ConceptSeries ocf, ConceptSeries assets) {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.conceptStrict("ACME", "NetIncomeLoss")).thenReturn(ni);
        when(f.conceptStrict("ACME", "NetCashProvidedByUsedInOperatingActivities")).thenReturn(ocf);
        when(f.conceptStrict("ACME", "Assets")).thenReturn(assets);
        return f;
    }

    @Test void computesRatioFromLatestMatchingAnnualPeriods() {
        var f = filings(
                new ConceptSeries("NetIncomeLoss", List.of(
                        duration("2024-01-01", "2024-12-31", "800"),
                        duration("2025-01-01", "2025-12-31", "1000"))),
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities", List.of(
                        duration("2025-01-01", "2025-12-31", "700"))),
                new ConceptSeries("Assets", List.of(
                        instant("2024-12-31", "9000"),
                        instant("2025-12-31", "10000"))));
        AccrualMetrics m = new SloanAccrualCalculator(f).accruals("ACME");
        assertThat(m.available()).isTrue();
        assertThat(m.accrualRatio()).isEqualByComparingTo("0.030000"); // (1000-700)/10000
    }

    @Test void unavailableWhenFlowPeriodsMismatch() {
        var f = filings(
                new ConceptSeries("NetIncomeLoss", List.of(duration("2025-01-01", "2025-12-31", "1000"))),
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities",
                        List.of(duration("2024-01-01", "2024-12-31", "700"))),
                new ConceptSeries("Assets", List.of(instant("2025-12-31", "10000"))));
        assertThat(new SloanAccrualCalculator(f).accruals("ACME").available()).isFalse();
    }

    @Test void ignoresNonAnnualDurationsAndDurationAssets() {
        var f = filings(
                new ConceptSeries("NetIncomeLoss", List.of(duration("2025-10-01", "2025-12-31", "250"))), // ~90d
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities",
                        List.of(duration("2025-01-01", "2025-12-31", "700"))),
                new ConceptSeries("Assets", List.of(duration("2025-01-01", "2025-12-31", "10000")))); // not instant
        assertThat(new SloanAccrualCalculator(f).accruals("ACME").available()).isFalse();
    }

    @Test void unavailableOnEmptySeriesOrZeroAssets() {
        var empty = filings(ConceptSeries.empty("NetIncomeLoss"),
                ConceptSeries.empty("NetCashProvidedByUsedInOperatingActivities"),
                ConceptSeries.empty("Assets"));
        assertThat(new SloanAccrualCalculator(empty).accruals("ACME").available()).isFalse();

        var zero = filings(
                new ConceptSeries("NetIncomeLoss", List.of(duration("2025-01-01", "2025-12-31", "1000"))),
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities",
                        List.of(duration("2025-01-01", "2025-12-31", "700"))),
                new ConceptSeries("Assets", List.of(instant("2025-12-31", "0"))));
        assertThat(new SloanAccrualCalculator(zero).accruals("ACME").available()).isFalse();
    }

    /** Before this task, {@code SloanAccrualCalculator} called the swallowing {@code concept()},
     *  whose own catch block absorbed an {@link AgoraUnavailableException} and returned an empty
     *  series — this class's try/catch could never see the exception, so the outage produced no
     *  WARN line of its own. Now it calls {@code conceptStrict()}, so a SOURCE-scoped outage
     *  reaches {@code fetchConcept} and logs the established "agora source unavailable" contract
     *  line, with the tag folded into the subject ({@code SYM:tag}) since three concepts are
     *  fetched per symbol and the tag is the only way to tell which one failed. */
    @Test void logsAgoraSourceUnavailableOnASourceScopedOutage() {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.conceptStrict("ACME", "NetIncomeLoss"))
                .thenThrow(new AgoraUnavailableException("Agora unreachable"));
        var calc = new SloanAccrualCalculator(f);

        List<ILoggingEvent> events = warningsWhile(() ->
                assertThat(calc.accruals("ACME").available()).isFalse());

        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .isEqualTo("agora source unavailable: tool=get_company_concept subject=ACME:NetIncomeLoss — Agora unreachable");
    }

    /** A REQUEST-scoped failure (Agora answered — an error envelope about this one request, e.g.
     *  an unresolvable CIK) must NOT log under the "agora source unavailable" prefix: that prefix
     *  is reserved for a genuine outage and becomes an alarm source, so a single unresolvable
     *  issuer logging under it would be a false alarm. Mirrors {@code AgoraFilings}' own {@code
     *  logSwallowed} branch on {@link AgoraUnavailableException.Scope}. */
    @Test void logsAgoraRequestFailedOnARequestScopedFailure() {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.conceptStrict("ACME", "NetIncomeLoss")).thenThrow(new AgoraUnavailableException(
                AgoraUnavailableException.Scope.REQUEST, "Agora tool error: no CIK for ACME", null));
        var calc = new SloanAccrualCalculator(f);

        List<ILoggingEvent> events = warningsWhile(() ->
                assertThat(calc.accruals("ACME").available()).isFalse());

        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo(
                "agora request failed: tool=get_company_concept subject=ACME:NetIncomeLoss — Agora tool error: no CIK for ACME");
    }
}
