package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

    private de.visterion.dracul.hunting.agora.AgoraCompanyData companyData(
            List<de.visterion.dracul.hunting.agora.RecommendationTrend> trend) {
        var d = mock(de.visterion.dracul.hunting.agora.AgoraCompanyData.class);
        when(d.recommendations(anyString())).thenReturn(trend);
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

    private ConfounderScreen confounders(List<String> flags) {
        ConfounderScreen c = mock(ConfounderScreen.class);
        when(c.confounders(anyString(), any())).thenReturn(flags);
        return c;
    }

    private EchoEnrichmentService service(List<QuarterlyEps> hist, SloanAccrualCalculator a,
                                          de.visterion.dracul.hunting.agora.AgoraCompanyData cd,
                                          AgoraEarnings n, ConfounderScreen ev) {
        return new EchoEnrichmentService(new SueEngine(), filings(), shaper(hist), marketData(),
                new MarketSignalService(), equityMetrics(), "SPY", 320,
                a, new RevisionsProxy(), cd, n, ev, new EchoDeterministicGate(new BigDecimal("0.10"), 10));
    }

    @Test
    void enrichesCleanCandidateWithSp3SoftFields() {
        var svc = service(historyFor(REPORT),
                accruals(new BigDecimal("0.04")), companyData(trend(7, 12)),
                nextEarnings(40), confounders(List.of()));
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
    }

    @Test
    void dropsAccrualDrivenCandidate() {
        var svc = service(historyFor(REPORT),
                accruals(new BigDecimal("0.25")), companyData(trend(1, 8)),
                nextEarnings(40), confounders(List.of()));
        assertThat(svc.enrich(List.of(cand("BAD", 1.80)))).isEmpty();
    }

    @Test
    void dropsConfoundedCandidate() {
        var svc = service(historyFor(REPORT),
                accruals(new BigDecimal("0.03")), companyData(trend(1, 8)),
                nextEarnings(40), confounders(List.of("m&a")));
        assertThat(svc.enrich(List.of(cand("MNA", 1.80)))).isEmpty();
    }

    @Test
    void dropsCandidateWithImminentNextEarnings() {
        var svc = service(historyFor(REPORT),
                accruals(new BigDecimal("0.03")), companyData(trend(1, 8)),
                nextEarnings(5), confounders(List.of()));
        assertThat(svc.enrich(List.of(cand("SOON", 1.80)))).isEmpty();
    }

    @Test
    void degradesWhenSp3SourcesUnavailableButStillKeeps() {
        var svc = service(List.of(),
                accruals(null), companyData(List.of()),
                nextEarnings(null), confounders(List.of()));
        var out = svc.enrich(List.of(cand("ZZZ", 1.80)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).accrualsAvailable()).isFalse();
        assertThat(out.get(0).revisionsAvailable()).isFalse();
        assertThat(out.get(0).daysToNextEarnings()).isNull();
        // SP1/SP2 still present
        assertThat(out.get(0).carAvailable()).isTrue();
        assertThat(out.get(0).coverageAvailable()).isFalse();
        assertThat(out.get(0).analystCoverage()).isNull();
    }
}
