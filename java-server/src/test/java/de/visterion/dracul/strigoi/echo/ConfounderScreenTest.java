package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfounderScreenTest {

    private static NewsHeadline news(String headline, String summary) {
        return new NewsHeadline(headline, summary, "src", "news",
                Instant.parse("2026-06-30T12:00:00Z"), "http://n");
    }

    private static AgoraCompanyData companyData(List<NewsHeadline> headlines) {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.newsResult(eq("ACME"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", headlines));
        return d;
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

    @Test void flagsDistinctCategoriesFromHeadlineAndSummary() {
        var screen = new ConfounderScreen(companyData(List.of(
                news("Acme agrees to merger with MegaCorp", ""),
                news("Acme announces takeover defense", ""),                      // same category, deduped
                news("Quarterly report", "company will restate prior results")))); // summary scanned too
        var probe = screen.confounders("ACME", LocalDate.now().minusDays(5));
        assertThat(probe.unknown()).isFalse();
        assertThat(probe.flags()).containsExactly("m&a", "restatement");
    }

    @Test void cleanNewsYieldsEmptyList() {
        var screen = new ConfounderScreen(companyData(List.of(news("Acme wins award", "nice quarter"))));
        var probe = screen.confounders("ACME", LocalDate.now().minusDays(5));
        assertThat(probe.unknown()).isFalse();
        assertThat(probe.flags()).isEmpty();
    }

    @Test void noNewsYieldsEmptyList() {
        var screen = new ConfounderScreen(companyData(List.of()));
        var probe = screen.confounders("ACME", LocalDate.now().minusDays(5));
        assertThat(probe.unknown()).isFalse();
        assertThat(probe.flags()).isEmpty();
    }

    @Test void earningsMissAndMacroHeadlinesProduceNoFlag() {
        // Daywalker-only types (spec §3): EARNINGS_MISS/MACRO must NOT block Echo.
        var screen = new ConfounderScreen(companyData(List.of(
                news("Acme misses estimates", "profit warning issued"),
                news("Fed raises rates", "tariffs and recession fears weigh"))));
        assertThat(screen.confounders("ACME", LocalDate.now().minusDays(5)).flags()).isEmpty();
    }

    @Test void flagsAreInHeadlineEncounterOrderNotEnumOrder() {
        // DILUTION is declared AFTER MA in the enum, but appears in the EARLIER headline —
        // encounter order (headline order) must win (spec §4.1/§4.4, R2-M2). This ordered
        // list is persisted via the Index path, so this test pins persisted behavior.
        var screen = new ConfounderScreen(companyData(List.of(
                news("Acme announces secondary offering", ""),
                news("Acme agrees to merger with MegaCorp", ""))));
        assertThat(screen.confounders("ACME", LocalDate.now().minusDays(5)).flags())
                .containsExactly("dilution", "m&a");
    }

    // --- T3: source-down must read as "unknown", never as "clean" (empty) ---

    @Test
    void reportsUnknownRatherThanCleanWhenTheNewsSourceIsDown() {
        AgoraCompanyData companyData = mock(AgoraCompanyData.class);
        when(companyData.newsResult(eq("ACME"), any(), any()))
                .thenReturn(DataSourceResult.unavailable("agora", "boom"));
        var screen = new ConfounderScreen(companyData);

        var probe = screen.confounders("ACME", LocalDate.now().minusDays(30));

        // "source was down" must NOT read as "no confounders found" — that is the exact
        // inversion this task exists to remove.
        assertThat(probe.unknown()).isTrue();
        assertThat(probe.flags()).isEmpty();
    }

    @Test
    void reportsCleanWhenTheSourceIsHealthyAndNothingMatched() {
        AgoraCompanyData companyData = mock(AgoraCompanyData.class);
        when(companyData.newsResult(eq("ACME"), any(), any()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        var screen = new ConfounderScreen(companyData);

        var probe = screen.confounders("ACME", LocalDate.now().minusDays(30));

        assertThat(probe.unknown()).isFalse();
        assertThat(probe.flags()).isEmpty();
    }

    @Test
    void sourceDownLogsAWarningSoTheOutageLeavesATrace() {
        // AgoraCompanyData#newsResult (unlike the swallowing #news) does not log itself, so without
        // a line here the source-down case would leave NO trace at all — less visible than before
        // this class started reading health instead of calling the swallowing facade.
        AgoraCompanyData companyData = mock(AgoraCompanyData.class);
        when(companyData.newsResult(eq("ACME"), any(), any()))
                .thenReturn(DataSourceResult.unavailable("agora", "boom"));
        var screen = new ConfounderScreen(companyData);

        var probe = new AtomicReference<ConfounderProbe>();
        var warnings = warningsWhile(ConfounderScreen.class,
                () -> probe.set(screen.confounders("ACME", LocalDate.now().minusDays(30))));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).isEqualTo(
                "confounder screen unknown: symbol=ACME — news source unavailable, "
                        + "absence of confounders NOT established");
        assertThat(probe.get().unknown()).isTrue();
    }

    @Test
    void healthySourceLogsNothingEvenWhenTheScanFindsNothing() {
        // The healthy/empty case must stay quiet — a WARN on every clean scan would drown the
        // real alarm signal (the source-down line above) in noise.
        AgoraCompanyData companyData = mock(AgoraCompanyData.class);
        when(companyData.newsResult(eq("ACME"), any(), any()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        var screen = new ConfounderScreen(companyData);

        var warnings = warningsWhile(ConfounderScreen.class,
                () -> screen.confounders("ACME", LocalDate.now().minusDays(30)));

        assertThat(warnings).isEmpty();
    }

    // --- T1.5: pure overload over an already-fetched headline list (spec §5.3/§7) ---

    @Test void pureOverloadScansAGivenHeadlineListWithoutFetching() {
        AgoraCompanyData d = mock(AgoraCompanyData.class); // deliberately unstubbed for .news()/.newsResult()
        var screen = new ConfounderScreen(d);

        var flags = screen.confounders(List.of(news("Acme agrees to merger with MegaCorp", "")));

        assertThat(flags).containsExactly("m&a");
        verifyNoInteractions(d);
    }

    @Test void pureOverloadOnEmptyListYieldsEmptyFlags() {
        var screen = new ConfounderScreen(mock(AgoraCompanyData.class));
        assertThat(screen.confounders(List.<NewsHeadline>of())).isEmpty();
    }
}
