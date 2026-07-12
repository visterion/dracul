package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.strigoi.echo.ConfounderScreen;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexDemandSnapshotterTest {

    private static final String SYM = "DEM";
    private static final String PROXY = "SPY";
    private static final int IDIO_LOOKBACK = 90;
    private static final int WINDOW = 180;                 // max(30, IDIO_LOOKBACK * 2)
    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate ANN = LocalDate.of(2026, 6, 1);

    private final ObjectMapper mapper = new ObjectMapper();

    private AgoraMarketData marketData;
    private EquityMetricsExtractor equityMetrics;
    private AgoraCompanyData companyData;
    private ConfounderScreen confounderScreen;
    private IndexDemandSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        marketData = mock(AgoraMarketData.class);
        equityMetrics = mock(EquityMetricsExtractor.class);
        companyData = mock(AgoraCompanyData.class);
        confounderScreen = mock(ConfounderScreen.class);

        // defaults: 30 flat stock + market bars (close 100 / 200, vol 1000), so adv & idio-vol resolve
        when(marketData.dailyOhlcHistory(eq(SYM), anyInt())).thenReturn(flatBars(30, 100, 1_000));
        when(marketData.dailyOhlcHistory(eq(PROXY), anyInt())).thenReturn(flatBars(30, 200, 1));
        when(equityMetrics.metricsWithoutSector(SYM)).thenReturn(new EquityMetrics(1.0, 6000.0, null, null, null, true));
        when(companyData.fundamentals(SYM)).thenReturn(mapper.readTree("{\"shareOutstanding\":50}"));
        when(confounderScreen.confounders(anyString(), any())).thenReturn(List.of("dilution"));

        snapshotter = new IndexDemandSnapshotter(marketData, equityMetrics, companyData, confounderScreen,
                PROXY, IDIO_LOOKBACK, 11500, 700, 350, 50000, 57000, 3500);
    }

    /** N consecutive flat daily bars (date = START + i). */
    private static List<OhlcBar> flatBars(int n, double close, long vol) {
        List<OhlcBar> out = new ArrayList<>();
        BigDecimal c = BigDecimal.valueOf(close);
        for (int i = 0; i < n; i++) out.add(new OhlcBar(START.plusDays(i), c, c, c, c, vol));
        return out;
    }

    @Test void happyPathComputesEveryFieldDeterministically() {
        var s = snapshotter.snapshot(SYM, "sp500", ANN);

        // adv = close(100) * vol(1000) = 100_000; avgVolume20d = 1000
        assertThat(s.adv()).isEqualByComparingTo("100000");
        assertThat(s.avgVolume20d()).isEqualTo(1_000L);
        assertThat(s.marketCap()).isEqualTo(6000.0);
        // constant residuals (flat closes) -> sample stddev exactly 0
        assertThat(s.idiosyncraticVol()).isEqualTo(0.0);
        // shares(50M) * lastClose(100) = 5000 USD millions
        assertThat(s.freeFloatProxyMillions()).isEqualTo(5000.0);
        assertThat(s.passiveAumTrackingBillions()).isEqualTo(11500.0);
        // (11500e9 * (5000e6 / 50000e9)) / 100000 = 11500 days
        assertThat(s.demandToAdvRatioEstimate()).isEqualTo(11500.0);
        assertThat(s.confounders()).containsExactly("dilution");
        assertThat(s.available()).isTrue();
    }

    @Test void idiosyncraticVolPinsTheSampleStdDevOfANonDegenerateSeries() {
        // Stock closes oscillate 100/101 while the market proxy stays flat (beta 1), so the 29 daily
        // residual returns alternate +0.01 / -(1/101) — a genuinely non-degenerate variance (unlike the
        // flat happy path's exact 0.0). The expected value is the SAMPLE (n-1) stddev of that series.
        // Pinning it exactly (tolerance << the n-vs-(n-1) gap of ~1.76e-4) means flipping the divisor
        // back to the biased population form (n) — which would yield ~0.0099446 — fails this test.
        when(marketData.dailyOhlcHistory(eq(SYM), anyInt())).thenReturn(oscillatingBars(30));

        var s = snapshotter.snapshot(SYM, "sp500", ANN);

        double expectedSampleStdDev = 0.010120601304043292;  // sqrt(SS/(n-1)); n=29 residuals
        assertThat(s.idiosyncraticVol())
                .as("idiosyncraticVol must be the n-1 sample stddev, not the n population stddev")
                .isNotNull()
                .isCloseTo(expectedSampleStdDev, within(1e-9));
        assertThat(s.available()).isTrue();
    }

    /** N daily bars whose close oscillates 100/101, so daily returns alternate sign (non-zero var). */
    private static List<OhlcBar> oscillatingBars(int n) {
        List<OhlcBar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = BigDecimal.valueOf(i % 2 == 0 ? 100 : 101);
            out.add(new OhlcBar(START.plusDays(i), c, c, c, c, 1_000));
        }
        return out;
    }

    @Test void priceSourceUnavailablePropagatesForTheBatchGuard() {
        when(marketData.dailyOhlcHistory(eq(SYM), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down"));

        assertThatThrownBy(() -> snapshotter.snapshot(SYM, "sp500", ANN))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void emptyBarsAreSymbolSpecificAndDegradePriceFieldsWithoutThrow() {
        when(marketData.dailyOhlcHistory(eq(SYM), anyInt())).thenReturn(List.of());

        var s = snapshotter.snapshot(SYM, "sp500", ANN);

        assertThat(s.adv()).isNull();
        assertThat(s.avgVolume20d()).isNull();
        assertThat(s.idiosyncraticVol()).isNull();
        assertThat(s.freeFloatProxyMillions()).isNull();      // no last close
        assertThat(s.demandToAdvRatioEstimate()).isNull();
        assertThat(s.marketCap()).isEqualTo(6000.0);          // independent swallowing source
        assertThat(s.confounders()).containsExactly("dilution");
        assertThat(s.available()).isFalse();
    }

    @Test void marketCapSourceUnavailableDegradesToNullWithoutThrow() {
        when(equityMetrics.metricsWithoutSector(SYM)).thenReturn(EquityMetrics.unavailable());

        var s = snapshotter.snapshot(SYM, "sp500", ANN);

        assertThat(s.marketCap()).isNull();
        assertThat(s.adv()).isEqualByComparingTo("100000");   // price fields still resolve
        assertThat(s.freeFloatProxyMillions()).isEqualTo(5000.0);
        assertThat(s.idiosyncraticVol()).isEqualTo(0.0);      // beta defaults to 1 when metrics gone
    }

    @Test void shareCountSourceUnavailableDropsFreeFloatAndDemandRatio() {
        when(companyData.fundamentals(SYM)).thenReturn(null);

        var s = snapshotter.snapshot(SYM, "sp500", ANN);

        assertThat(s.freeFloatProxyMillions()).isNull();
        assertThat(s.demandToAdvRatioEstimate()).isNull();    // needs free float
        assertThat(s.adv()).isEqualByComparingTo("100000");
        assertThat(s.marketCap()).isEqualTo(6000.0);
    }

    @Test void unconfiguredIndexLeavesAumAndDemandRatioNull() {
        var s = snapshotter.snapshot(SYM, "nasdaq100", ANN);

        assertThat(s.passiveAumTrackingBillions()).isNull();
        assertThat(s.demandToAdvRatioEstimate()).isNull();
        assertThat(s.adv()).isEqualByComparingTo("100000");   // other fields unaffected
    }
}
