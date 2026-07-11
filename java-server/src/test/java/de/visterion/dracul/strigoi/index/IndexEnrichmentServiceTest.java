package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexEnrichmentServiceTest {

    private static IndexCandidate cand(String sym) {
        return new IndexCandidate(sym, sym + " Inc.", LocalDate.now().minusDays(5).toString());
    }

    /** 25 bars, close 10, volume 1_000_000 -> adv = 10_000_000, avgVolume20d = 1_000_000. */
    private static List<OhlcBar> bars() {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(24);
        for (int i = 0; i < 25; i++) {
            BigDecimal c = BigDecimal.TEN;
            out.add(new OhlcBar(start.plusDays(i), c, c, c, c, 1_000_000L));
        }
        return out;
    }

    private static AgoraMarketData marketDataReturning(List<OhlcBar> b) {
        return new AgoraMarketData(null) {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) { return b; }
        };
    }

    private static AgoraMarketData marketDataThrowing() {
        return new AgoraMarketData(null) {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "outage");
            }
        };
    }

    private static EquityMetricsExtractor equityMetrics(Double marketCap, boolean available) {
        EquityMetricsExtractor m = mock(EquityMetricsExtractor.class);
        when(m.metrics(anyString())).thenReturn(
                available ? new EquityMetrics(1.0, marketCap, 100.0, 200.0, "Technology", true)
                        : EquityMetrics.unavailable());
        return m;
    }

    @Test
    void enrichesAdvMarketCapAndVolume() {
        var svc = new IndexEnrichmentService(marketDataReturning(bars()), equityMetrics(2_500_000.0, true));

        var out = svc.enrich(List.of(cand("AAA")));

        assertThat(out).hasSize(1);
        var e = out.get(0);
        assertThat(e.symbol()).isEqualTo("AAA");
        assertThat(e.adv()).isEqualByComparingTo("10000000");
        assertThat(e.avgVolume20d()).isEqualTo(1_000_000L);
        assertThat(e.marketCap()).isEqualTo(2_500_000.0);
        assertThat(e.metricsAvailable()).isTrue();
    }

    @Test
    void degradesToNullsWhenOhlcUnavailable() {
        var svc = new IndexEnrichmentService(marketDataThrowing(), equityMetrics(null, false));

        var out = svc.enrich(List.of(cand("BBB")));

        assertThat(out).hasSize(1);
        var e = out.get(0);
        assertThat(e.symbol()).isEqualTo("BBB");
        assertThat(e.adv()).isNull();
        assertThat(e.avgVolume20d()).isNull();
        assertThat(e.marketCap()).isNull();
        assertThat(e.metricsAvailable()).isFalse();
    }

    @Test
    void capsAtTwentyFiveCandidates() {
        var svc = new IndexEnrichmentService(marketDataReturning(bars()), equityMetrics(2_500_000.0, true));

        List<IndexCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 30; i++) candidates.add(cand("SYM" + i));

        var out = svc.enrich(candidates);

        assertThat(out).hasSize(25);
    }
}
