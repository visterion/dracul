package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    private EpsHistoryPort historyPort(List<QuarterlyEps> q) {
        return (symbol, max) -> q;
    }

    /** 25 consecutive-day bars ending today; report at index 22 (d0) so CAR + abnormal
     *  volume are available. Stock +5% on report day; SPY flat. */
    private static List<OhlcBar> stockBars() {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(24);
        for (int i = 0; i < 25; i++) {
            double close = (i == 22 || i == 23 || i == 24) ? 199.5 : 190.0; // +~5% from idx21 (190) to idx22
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

    private MarketDataPort marketData() {
        return new MarketDataPort() {
            @Override public MarketData resolve(String symbol) {
                throw new UnsupportedOperationException("not used");
            }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
                return "SPY".equals(symbol) ? spyBars() : stockBars();
            }
        };
    }

    private EquityMetricsPort equityMetrics() {
        return symbol -> new EquityMetrics(1.0, 2_500_000.0, 150.0, 260.0, "Technology", true);
    }

    private EchoEnrichmentService service(EpsHistoryPort hist) {
        return new EchoEnrichmentService(new SueEngine(), hist, marketData(),
                new MarketSignalService(), equityMetrics(), "SPY", 320);
    }

    @Test
    void enrichesWithSp1AndSp2Signals() {
        var c = cand("AAPL", 1.80);
        var out = service(historyPort(historyFor(REPORT))).enrich(List.of(c));
        assertThat(out).hasSize(1);
        var e = out.get(0);
        // SP1 unchanged
        assertThat(e.sueAvailable()).isTrue();
        assertThat(e.revenueSurprisePercent()).isGreaterThan(BigDecimal.ZERO);
        assertThat(e.doubleBeat()).isTrue();
        assertThat(e.consecutiveBeats()).isEqualTo(4);
        // SP2
        assertThat(e.carAvailable()).isTrue();
        assertThat(e.announcementCar1d()).isNotNull().isGreaterThan(BigDecimal.ZERO);
        assertThat(e.abnormalVolume()).isEqualByComparingTo("2"); // report-day vol 2x
        assertThat(e.metricsAvailable()).isTrue();
        assertThat(e.beta()).isEqualTo(1.0);
        assertThat(e.marketCap()).isEqualTo(2_500_000.0);
        assertThat(e.sector()).isEqualTo("Technology");
    }

    @Test
    void degradesWhenNoHistory() {
        var out = service(historyPort(List.of())).enrich(List.of(cand("ZZZ", 1.80)));
        assertThat(out.get(0).sueAvailable()).isFalse();
        assertThat(out.get(0).sueDecile()).isNull();
        assertThat(out.get(0).consecutiveBeats()).isNull();
        // SP2 still computed (OHLC + metrics independent of EPS history)
        assertThat(out.get(0).carAvailable()).isTrue();
        assertThat(out.get(0).metricsAvailable()).isTrue();
    }

    @Test
    void degradesWhenMarketDataThrows() {
        MarketDataPort throwing = new MarketDataPort() {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
                throw new de.visterion.dracul.marketdata.MarketDataException(
                        de.visterion.dracul.marketdata.MarketDataException.Kind.UNAVAILABLE, "boom", null);
            }
        };
        var svc = new EchoEnrichmentService(new SueEngine(), historyPort(historyFor(REPORT)),
                throwing, new MarketSignalService(),
                s -> EquityMetrics.unavailable(), "SPY", 320);
        var e = svc.enrich(List.of(cand("AAPL", 1.80))).get(0);
        assertThat(e.carAvailable()).isFalse();
        assertThat(e.announcementCar1d()).isNull();
        assertThat(e.metricsAvailable()).isFalse();
    }
}
