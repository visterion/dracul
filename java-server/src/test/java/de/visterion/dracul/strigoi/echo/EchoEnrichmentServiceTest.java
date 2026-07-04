package de.visterion.dracul.strigoi.echo;

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

    private EpsHistoryPort historyPort(List<QuarterlyEps> q) { return (symbol, max) -> q; }

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

    private EquityMetricsPort equityMetrics() {
        return symbol -> new EquityMetrics(1.0, 2_500_000.0, 150.0, 260.0, "Technology", true);
    }

    // --- SP3 stubs (configurable per test) ---
    private FundamentalsPort fundamentals(BigDecimal ratio) {
        return symbol -> ratio == null ? AccrualMetrics.unavailable() : new AccrualMetrics(ratio, true);
    }
    private RevisionPort revisions(int proxy, String dir) {
        return symbol -> new EarningsRevisions(proxy, dir, true);
    }
    private NextEarningsPort nextEarnings(Integer daysAhead) {
        return symbol -> daysAhead == null ? Optional.empty()
                : Optional.of(LocalDate.now().plusDays(daysAhead));
    }
    private EventScreenPort eventScreen(List<String> flags) {
        return (symbol, since) -> flags;
    }

    private EchoEnrichmentService service(EpsHistoryPort hist, FundamentalsPort f, RevisionPort r,
                                          NextEarningsPort n, EventScreenPort ev) {
        return new EchoEnrichmentService(new SueEngine(), hist, marketData(), new MarketSignalService(),
                equityMetrics(), "SPY", 320,
                f, r, n, ev, new EchoDeterministicGate(new BigDecimal("0.10"), 10));
    }

    @Test
    void enrichesCleanCandidateWithSp3SoftFields() {
        var svc = service(historyPort(historyFor(REPORT)),
                fundamentals(new BigDecimal("0.04")), revisions(7, "up"),
                nextEarnings(40), eventScreen(List.of()));
        var out = svc.enrich(List.of(cand("AAPL", 1.80)));
        assertThat(out).hasSize(1);
        var e = out.get(0);
        assertThat(e.accrualsAvailable()).isTrue();
        assertThat(e.accrualRatio()).isEqualByComparingTo("0.04");
        assertThat(e.netEstimateRevisionsProxy()).isEqualTo(7);
        assertThat(e.guidanceDirection()).isEqualTo("up");
        assertThat(e.revisionsAvailable()).isTrue();
        assertThat(e.nextEarningsDate()).isEqualTo(LocalDate.now().plusDays(40));
        assertThat(e.daysToNextEarnings()).isEqualTo(40);
    }

    @Test
    void dropsAccrualDrivenCandidate() {
        var svc = service(historyPort(historyFor(REPORT)),
                fundamentals(new BigDecimal("0.25")), revisions(1, "up"),
                nextEarnings(40), eventScreen(List.of()));
        assertThat(svc.enrich(List.of(cand("BAD", 1.80)))).isEmpty();
    }

    @Test
    void dropsConfoundedCandidate() {
        var svc = service(historyPort(historyFor(REPORT)),
                fundamentals(new BigDecimal("0.03")), revisions(1, "up"),
                nextEarnings(40), eventScreen(List.of("m&a")));
        assertThat(svc.enrich(List.of(cand("MNA", 1.80)))).isEmpty();
    }

    @Test
    void dropsCandidateWithImminentNextEarnings() {
        var svc = service(historyPort(historyFor(REPORT)),
                fundamentals(new BigDecimal("0.03")), revisions(1, "up"),
                nextEarnings(5), eventScreen(List.of()));
        assertThat(svc.enrich(List.of(cand("SOON", 1.80)))).isEmpty();
    }

    @Test
    void degradesWhenSp3SourcesUnavailableButStillKeeps() {
        var svc = service(historyPort(List.of()),
                fundamentals(null), symbol -> EarningsRevisions.unavailable(),
                nextEarnings(null), eventScreen(List.of()));
        var out = svc.enrich(List.of(cand("ZZZ", 1.80)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).accrualsAvailable()).isFalse();
        assertThat(out.get(0).revisionsAvailable()).isFalse();
        assertThat(out.get(0).daysToNextEarnings()).isNull();
        // SP1/SP2 still present
        assertThat(out.get(0).carAvailable()).isTrue();
    }
}
