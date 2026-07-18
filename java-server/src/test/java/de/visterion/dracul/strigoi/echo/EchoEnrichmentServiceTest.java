package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EchoEnrichmentServiceTest {

    private static final LocalDate REPORT = LocalDate.now().minusDays(2);

    private static PeadCandidate cand(String sym, double actual) {
        return new PeadCandidate(sym, sym + " Inc.", REPORT,
                BigDecimal.valueOf(actual), BigDecimal.valueOf(1.20), BigDecimal.valueOf(10.0),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(900), BigDecimal.valueOf(190));
    }

    private static List<QuarterlyEps> historyFor(LocalDate reportDate) {
        double[] eps = {2.00, 1.85, 1.70, 1.60, 1.50, 1.40, 1.30, 1.25};
        List<QuarterlyEps> h = new ArrayList<>();
        for (int k = 0; k < eps.length; k++) {
            h.add(new QuarterlyEps(reportDate.minusDays(120L + 91L * k), BigDecimal.valueOf(eps[k])));
        }
        return h;
    }

    private static List<OhlcBar> stockBars() {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(24);
        for (int i = 0; i < 25; i++) {
            double close = (i == 22 || i == 23 || i == 24) ? 199.5 : 190.0;
            BigDecimal c = BigDecimal.valueOf(close);
            out.add(new OhlcBar(start.plusDays(i), c, c, c, c, 1_000L * (i == 22 ? 2 : 1)));
        }
        return out;
    }

    private static List<OhlcBar> spyBars() {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(24);
        for (int i = 0; i < 25; i++) {
            BigDecimal c = BigDecimal.valueOf(500.0);
            out.add(new OhlcBar(start.plusDays(i), c, c, c, c, 1L));
        }
        return out;
    }

    private AgoraMarketData marketData() {
        return new AgoraMarketData(null) {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
                return "SPY".equals(symbol) ? spyBars() : stockBars();
            }
        };
    }

    // --- stubbed helpers/facades (configurable per test) ---

    private AgoraFilings filings() {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.epsHistory(anyString())).thenReturn(ConceptSeries.empty("eps"));
        return f;
    }

    private EpsHistoryShaper shaper(List<QuarterlyEps> hist) {
        EpsHistoryShaper s = mock(EpsHistoryShaper.class);
        when(s.quarterly(any(), anyInt())).thenReturn(hist);
        return s;
    }

    private EquityMetricsExtractor equityMetrics() {
        EquityMetricsExtractor m = mock(EquityMetricsExtractor.class);
        when(m.metrics(anyString()))
                .thenReturn(new EquityMetrics(1.0, 2_500_000.0, 150.0, 260.0, "Technology", true));
        return m;
    }

    private SloanAccrualCalculator accruals(BigDecimal ratio) {
        SloanAccrualCalculator a = mock(SloanAccrualCalculator.class);
        when(a.accruals(anyString())).thenReturn(
                ratio == null ? AccrualMetrics.unavailable() : new AccrualMetrics(ratio, true));
        return a;
    }

    /** Mocked {@link de.visterion.dracul.hunting.agora.AgoraCompanyData} carrying BOTH the
     *  recommendation trend AND the news list — the single facade the real
     *  {@link ConfounderScreen} (constructed over this same mock) and the service's own
     *  {@code safeNews} fetch both read from, so the fetch-count assertions actually bind. */
    private de.visterion.dracul.hunting.agora.AgoraCompanyData companyData(
            List<de.visterion.dracul.hunting.agora.RecommendationTrend> trend, List<NewsHeadline> news) {
        var d = mock(de.visterion.dracul.hunting.agora.AgoraCompanyData.class);
        when(d.recommendations(anyString())).thenReturn(trend);
        when(d.news(anyString(), any(), any())).thenReturn(news);
        return d;
    }

    /** A two-period trend whose latestNet-prevNet == proxy and whose latest-period sum == coverage. */
    private static List<de.visterion.dracul.hunting.agora.RecommendationTrend> trend(int proxy, int coverage) {
        int hold = Math.max(0, coverage - Math.max(proxy, 0));
        return List.of(
                new de.visterion.dracul.hunting.agora.RecommendationTrend("p2", Math.max(proxy, 0), 0, hold, Math.max(-proxy, 0), 0),
                new de.visterion.dracul.hunting.agora.RecommendationTrend("p1", 0, 0, 0, 0, 0));
    }

    private AgoraEarnings nextEarnings(Integer daysAhead) {
        AgoraEarnings e = mock(AgoraEarnings.class);
        when(e.nextEarningsDate(anyString())).thenReturn(
                daysAhead == null ? Optional.empty() : Optional.of(LocalDate.now().plusDays(daysAhead)));
        return e;
    }

    private static NewsHeadline headline(String text, Instant when) {
        return new NewsHeadline(text, "", "src", "news", when, "http://n", "example.com", 0.8);
    }

    /** {@code n} clean headlines, newest first, one day apart, ending at {@code REPORT} + 1d. */
    private static List<NewsHeadline> cleanHeadlines(int n) {
        List<NewsHeadline> out = new ArrayList<>();
        Instant newest = REPORT.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        for (int i = 0; i < n; i++) {
            out.add(headline("Acme posts routine update #" + i, newest.minus(i, ChronoUnit.DAYS)));
        }
        return out;
    }

    private EchoEnrichmentService service(List<QuarterlyEps> hist, SloanAccrualCalculator a,
                                          de.visterion.dracul.hunting.agora.AgoraCompanyData cd,
                                          AgoraEarnings n) {
        return new EchoEnrichmentService(new SueEngine(), filings(), shaper(hist), marketData(),
                new MarketSignalService(), equityMetrics(), "SPY", 320,
                a, new RevisionsProxy(), cd, n, new ConfounderScreen(cd),
                new EchoDeterministicGate(new BigDecimal("0.10"), 10), 10);
    }

    @Test
    void enrichesCleanCandidateWithSp3SoftFields() {
        var cd = companyData(trend(7, 12), cleanHeadlines(3));
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.04")), cd, nextEarnings(40));
        var out = svc.enrich(List.of(cand("AAPL", 1.80)));
        assertThat(out).hasSize(1);
        var e = out.get(0);
        assertThat(e.accrualsAvailable()).isTrue();
        assertThat(e.accrualRatio()).isEqualByComparingTo("0.04");
        assertThat(e.netEstimateRevisionsProxy()).isEqualTo(7);
        assertThat(e.netEstimateRevisionsDirection()).isEqualTo("up");
        assertThat(e.revisionsAvailable()).isTrue();
        assertThat(e.nextEarningsDate()).isEqualTo(LocalDate.now().plusDays(40));
        assertThat(e.daysToNextEarnings()).isEqualTo(40);
        assertThat(e.coverageAvailable()).isTrue();
        assertThat(e.analystCoverage()).isEqualTo(12);
        assertThat(e.recentNews()).hasSize(3);
    }

    @Test
    void dropsAccrualDrivenCandidate() {
        var cd = companyData(trend(1, 8), cleanHeadlines(1));
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.25")), cd, nextEarnings(40));
        assertThat(svc.enrich(List.of(cand("BAD", 1.80)))).isEmpty();
    }

    @Test
    void dropsConfoundedCandidate() {
        var news = List.of(headline("Acme agrees to merger with MegaCorp", REPORT.plusDays(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        var cd = companyData(trend(1, 8), news);
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.03")), cd, nextEarnings(40));
        assertThat(svc.enrich(List.of(cand("MNA", 1.80)))).isEmpty();
    }

    @Test
    void dropsConfoundedCandidateEvenWhenTheConfounderIsBeyondTheRecentNewsCap() {
        // 10 clean, newer headlines fill recentNews's N=10 cap; the confounder is the OLDEST
        // (11th-most-recent) headline, ranked beyond the cap. The gate must still see it,
        // because ConfounderScreen scans the FULL uncapped fetch, not the capped recentNews
        // list (Major-2 regression guard, spec §5.3/§7).
        Instant newest = REPORT.plusDays(10).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<NewsHeadline> news = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            news.add(headline("Acme posts routine update #" + i, newest.minus(i, ChronoUnit.DAYS)));
        }
        news.add(headline("Acme agrees to merger with MegaCorp", newest.minus(11, ChronoUnit.DAYS)));

        var cd = companyData(trend(1, 8), news);
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.03")), cd, nextEarnings(40));
        assertThat(svc.enrich(List.of(cand("MNA2", 1.80))))
                .as("confounder beyond the recentNews cap must still gate-skip")
                .isEmpty();
    }

    @Test
    void recentNewsIsCappedAtTenMostRecentDescending() {
        // 13 distinct-datetime clean headlines; only the 10 most recent survive, newest first.
        Instant newest = REPORT.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<NewsHeadline> news = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            news.add(headline("Acme headline #" + i, newest.minus(i, ChronoUnit.DAYS)));
        }
        var cd = companyData(trend(1, 8), news);
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.03")), cd, nextEarnings(40));

        var out = svc.enrich(List.of(cand("MANY", 1.80)));
        assertThat(out).hasSize(1);
        List<EchoNewsItem> recentNews = out.get(0).recentNews();
        assertThat(recentNews).hasSize(10);
        assertThat(recentNews.get(0).headline()).isEqualTo("Acme headline #0");
        assertThat(recentNews.get(9).headline()).isEqualTo("Acme headline #9");
        for (int i = 0; i < recentNews.size() - 1; i++) {
            assertThat(recentNews.get(i).datetime()).isAfter(recentNews.get(i + 1).datetime());
        }
    }

    @Test
    void newsIsFetchedExactlyOncePerSymbol() {
        var cd = companyData(trend(1, 8), cleanHeadlines(2));
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.03")), cd, nextEarnings(40));

        svc.enrich(List.of(cand("ONE", 1.80)));

        verify(cd, times(1)).news(eq("ONE"), any(), any());
    }

    @Test
    void dropsCandidateWithImminentNextEarnings() {
        var cd = companyData(trend(1, 8), cleanHeadlines(1));
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.03")), cd, nextEarnings(5));
        assertThat(svc.enrich(List.of(cand("SOON", 1.80)))).isEmpty();
    }

    @Test
    void degradesWhenSp3SourcesUnavailableButStillKeeps() {
        var cd = companyData(List.of(), List.of());
        var svc = service(List.of(), accruals(null), cd, nextEarnings(null));
        var out = svc.enrich(List.of(cand("ZZZ", 1.80)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).accrualsAvailable()).isFalse();
        assertThat(out.get(0).revisionsAvailable()).isFalse();
        assertThat(out.get(0).daysToNextEarnings()).isNull();
        // SP1/SP2 still present
        assertThat(out.get(0).carAvailable()).isTrue();
        assertThat(out.get(0).coverageAvailable()).isFalse();
        assertThat(out.get(0).analystCoverage()).isNull();
        assertThat(out.get(0).recentNews()).isEmpty();
    }

    @Test
    void agoraNewsFailureDegradesToEmptyRecentNewsAndDoesNotThrow() {
        var cd = mock(de.visterion.dracul.hunting.agora.AgoraCompanyData.class);
        when(cd.recommendations(anyString())).thenReturn(trend(1, 8));
        when(cd.news(anyString(), any(), any())).thenThrow(new RuntimeException("agora down"));
        var svc = service(historyFor(REPORT), accruals(new BigDecimal("0.03")), cd, nextEarnings(40));

        var out = svc.enrich(List.of(cand("DOWN", 1.80)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).recentNews()).isEmpty();
    }

    @Test
    void negativeRecentNewsCapClampsToEmptyInsteadOfThrowing() {
        // A misconfigured negative dracul.strigoi.echo.recent-news-cap must not abort the whole
        // enrich run via Stream.limit's IllegalArgumentException (Minor-2 regression guard).
        var cd = companyData(trend(1, 8), cleanHeadlines(3));
        var svc = new EchoEnrichmentService(new SueEngine(), filings(), shaper(historyFor(REPORT)), marketData(),
                new MarketSignalService(), equityMetrics(), "SPY", 320,
                accruals(new BigDecimal("0.03")), new RevisionsProxy(), cd, nextEarnings(40),
                new ConfounderScreen(cd), new EchoDeterministicGate(new BigDecimal("0.10"), 10), -1);

        var out = svc.enrich(List.of(cand("NEG", 1.80)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).recentNews()).isEmpty();
    }

    @Test
    void confounderScanFailureDegradesOneCandidateAndContinuesTheBatch() {
        // Simulates an unexpected bug in the keyword-tagging step (not an Agora fetch failure,
        // which is guarded separately by safeNews). Per-candidate degradation must fail open
        // (empty confounders/recentNews) for the broken candidate and still process the rest of
        // the batch (Minor-1 regression guard).
        var cd = companyData(trend(1, 8), cleanHeadlines(2));
        ConfounderScreen brokenScreen = mock(ConfounderScreen.class);
        when(brokenScreen.confounders(org.mockito.ArgumentMatchers.<NewsHeadline>anyList()))
                .thenThrow(new RuntimeException("tagger blew up"));
        var svc = new EchoEnrichmentService(new SueEngine(), filings(), shaper(historyFor(REPORT)), marketData(),
                new MarketSignalService(), equityMetrics(), "SPY", 320,
                accruals(new BigDecimal("0.03")), new RevisionsProxy(), cd, nextEarnings(40),
                brokenScreen, new EchoDeterministicGate(new BigDecimal("0.10"), 10), 10);

        var out = svc.enrich(List.of(cand("BROKEN", 1.80), cand("FINE", 1.80)));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).recentNews()).isEmpty();
        assertThat(out.get(1).recentNews()).isEmpty(); // both share the same mocked, always-throwing screen
    }
}
