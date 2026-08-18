package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.news.NewsCredibilityProperties;
import de.visterion.dracul.hunting.news.NewsCredibilityScorer;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.lazarus.FundamentalScore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pins that the nine swallowing catch blocks in {@link AgoraCompanyData} and {@link AgoraFilings}
 * both log the outage AND keep their exact pre-existing return contract. The second assertion is
 * the more important one: this task must not change what any of these nine methods returns.
 *
 * <p>Also pins the SOURCE/REQUEST branch: a {@link AgoraUnavailableException.Scope#SOURCE}
 * failure (Agora never answered) logs {@code agora source unavailable:}; a
 * {@link AgoraUnavailableException.Scope#REQUEST} failure (Agora answered with an error envelope
 * about this one request — e.g. an oversized filing) logs {@code agora request failed:} instead.
 * Folding the two into one prefix would make the outage prefix worthless as a future alarm
 * source, since a single oversized document would then look exactly like a dead source.
 */
class AgoraFacadeOutageLoggingTest {

    private static NewsCredibilityProperties testProps() {
        return new NewsCredibilityProperties(0.5, 0.3, List.of());
    }

    private static AgoraCompanyData companyData(AgoraClient client) {
        NewsCredibilityProperties props = testProps();
        return new AgoraCompanyData(client, false, new NewsCredibilityScorer(props), props);
    }

    private List<String> warningsWhile(Class<?> loggerClass, Runnable body) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void newsLogsTheOutageAndStillReturnsAnEmptyListUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_company_news"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraCompanyData companyData = companyData(agora);

        var result = new AtomicReference<List<NewsHeadline>>();
        var warnings = warningsWhile(AgoraCompanyData.class,
                () -> result.set(companyData.news("ACME", LocalDate.now().minusDays(7), LocalDate.now())));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_company_news subject=ACME — boom");
        assertThat(result.get()).isEmpty();
    }

    @Test
    void newsWithRequestScopeLogsRequestFailedNotSourceUnavailable() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_company_news"), any()))
                .thenThrow(new AgoraUnavailableException(
                        AgoraUnavailableException.Scope.REQUEST, "unknown symbol", null));
        AgoraCompanyData companyData = companyData(agora);

        var result = new AtomicReference<List<NewsHeadline>>();
        var warnings = warningsWhile(AgoraCompanyData.class,
                () -> result.set(companyData.news("ACME", LocalDate.now().minusDays(7), LocalDate.now())));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora request failed: tool=get_company_news subject=ACME — unknown symbol");
        assertThat(result.get()).isEmpty();
    }

    @Test
    void recommendationsLogsTheOutageAndStillReturnsAnEmptyListUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_analyst_estimates"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraCompanyData companyData = companyData(agora);

        var result = new AtomicReference<List<RecommendationTrend>>();
        var warnings = warningsWhile(AgoraCompanyData.class,
                () -> result.set(companyData.recommendations("ACME")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_analyst_estimates subject=ACME — boom");
        assertThat(result.get()).isEmpty();
    }

    @Test
    void fundamentalsLogsTheOutageAndStillReturnsNullUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_fundamentals"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraCompanyData companyData = companyData(agora);

        var result = new AtomicReference<tools.jackson.databind.JsonNode>();
        var warnings = warningsWhile(AgoraCompanyData.class,
                () -> result.set(companyData.fundamentals("ACME")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_fundamentals subject=ACME — boom");
        assertThat(result.get()).isNull();
    }

    @Test
    void profileLogsTheOutageAndStillReturnsNullUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_company_profile"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraCompanyData companyData = companyData(agora);

        var result = new AtomicReference<tools.jackson.databind.JsonNode>();
        var warnings = warningsWhile(AgoraCompanyData.class,
                () -> result.set(companyData.profile("ACME")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_company_profile subject=ACME — boom");
        assertThat(result.get()).isNull();
    }

    @Test
    void conceptLogsTheOutageAndStillReturnsAnEmptySeriesUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_company_concept"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraFilings filings = new AgoraFilings(agora);

        var result = new AtomicReference<ConceptSeries>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.concept("ACME", "Assets")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_company_concept subject=ACME:Assets — boom");
        assertThat(result.get()).isEqualTo(ConceptSeries.empty("Assets"));
    }

    @Test
    void conceptWithRequestScopeLogsRequestFailedNotSourceUnavailable() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_company_concept"), any()))
                .thenThrow(new AgoraUnavailableException(
                        AgoraUnavailableException.Scope.REQUEST, "unresolvable cik", null));
        AgoraFilings filings = new AgoraFilings(agora);

        var result = new AtomicReference<ConceptSeries>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.concept("ACME", "Assets")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora request failed: tool=get_company_concept subject=ACME:Assets — unresolvable cik");
        assertThat(result.get()).isEqualTo(ConceptSeries.empty("Assets"));
    }

    @Test
    void epsHistoryLogsTheOutageAndStillReturnsAnEmptySeriesUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_eps_history"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraFilings filings = new AgoraFilings(agora);

        var result = new AtomicReference<ConceptSeries>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.epsHistory("ACME")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_eps_history subject=ACME — boom");
        assertThat(result.get()).isEqualTo(ConceptSeries.empty("eps"));
    }

    @Test
    void fundamentalScoreLogsTheOutageAndStillReturnsUnavailableUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_fundamental_score"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraFilings filings = new AgoraFilings(agora);

        var result = new AtomicReference<FundamentalScore>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.fundamentalScore("ACME")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_fundamental_score subject=ACME — boom");
        assertThat(result.get()).isEqualTo(FundamentalScore.unavailable());
    }

    @Test
    void filingTextLogsTheOutageAndStillReturnsUnavailableUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_filing_text"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraFilings filings = new AgoraFilings(agora);
        String url = "https://www.sec.gov/Archives/edgar/data/1/x.htm";

        var result = new AtomicReference<FilingText>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.filingText(url)));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_filing_text subject=" + url + " — boom");
        assertThat(result.get()).isEqualTo(FilingText.unavailable());
    }

    @Test
    void filingTextWithFilingTooLargeLogsRequestFailedAndReturnsTooLarge() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        String tooLargeMessage = AgoraUnavailableException.FILING_TOO_LARGE_TOKEN + " document exceeds max size";
        when(agora.callTool(eq("get_filing_text"), any()))
                .thenThrow(new AgoraUnavailableException(
                        AgoraUnavailableException.Scope.REQUEST, tooLargeMessage, null));
        AgoraFilings filings = new AgoraFilings(agora);
        String url = "https://www.sec.gov/Archives/edgar/data/1/x.htm";

        var result = new AtomicReference<FilingText>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.filingText(url)));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora request failed: tool=get_filing_text subject=" + url + " — " + tooLargeMessage);
        // This is the real case the design flaw was found on: a too-large filing is REQUEST-scoped
        // (Agora is healthy), and the return stays FilingText.tooLarge(), not the plain unavailable().
        assertThat(result.get()).isEqualTo(FilingText.tooLarge());
    }

    @Test
    void filingTextWithExhibitLogsTheOutageAndStillReturnsUnavailableUnchanged() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_filing_text"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));
        AgoraFilings filings = new AgoraFilings(agora);
        String url = "https://www.sec.gov/Archives/edgar/data/1/x.htm";

        var result = new AtomicReference<FilingText>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.filingText(url, "EX-99.1", "LEADING")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora source unavailable: tool=get_filing_text subject=" + url + " — boom");
        assertThat(result.get()).isEqualTo(FilingText.unavailable());
    }

    @Test
    void filingTextWithExhibitAndFilingTooLargeLogsRequestFailedAndReturnsTooLarge() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        String tooLargeMessage = AgoraUnavailableException.FILING_TOO_LARGE_TOKEN + " document exceeds max size";
        when(agora.callTool(eq("get_filing_text"), any()))
                .thenThrow(new AgoraUnavailableException(
                        AgoraUnavailableException.Scope.REQUEST, tooLargeMessage, null));
        AgoraFilings filings = new AgoraFilings(agora);
        String url = "https://www.sec.gov/Archives/edgar/data/1/x.htm";

        var result = new AtomicReference<FilingText>();
        var warnings = warningsWhile(AgoraFilings.class,
                () -> result.set(filings.filingText(url, "EX-99.1", "LEADING")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("agora request failed: tool=get_filing_text subject=" + url + " — " + tooLargeMessage);
        assertThat(result.get()).isEqualTo(FilingText.tooLarge());
    }
}
