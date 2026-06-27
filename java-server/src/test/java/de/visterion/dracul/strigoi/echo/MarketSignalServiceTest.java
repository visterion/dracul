package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketSignalServiceTest {

    private final MarketSignalService svc = new MarketSignalService();
    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    /** Consecutive-day bars with given close + volume per index (date = START + i days). */
    private static List<OhlcBar> bars(double[] closes, long[] volumes) {
        List<OhlcBar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            BigDecimal c = BigDecimal.valueOf(closes[i]);
            out.add(new OhlcBar(START.plusDays(i), c, c, c, c, volumes[i]));
        }
        return out;
    }

    private static double[] flat(int n, double v) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static long[] flatVol(int n, long v) {
        long[] a = new long[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    @Test
    void computesMarketAdjustedCarAndAbnormalVolume() {
        // 23 bars; report on index 20 (d0). prior close idx19. Stock +5% on d0, flat after.
        double[] s = flat(23, 100); s[19] = 100; s[20] = 105; s[21] = 105; s[22] = 105;
        long[] sv = flatVol(23, 1_000); sv[20] = 2_000; // report-day volume doubles
        double[] m = flat(23, 200); m[19] = 200; m[20] = 202; m[21] = 202; m[22] = 202; // SPY +1% on d0
        long[] mv = flatVol(23, 1);
        LocalDate report = START.plusDays(20);

        MarketSignals sig = svc.compute(bars(s, sv), bars(m, mv), report, null);

        assertThat(sig.carAvailable()).isTrue();
        assertThat(sig.announcementCar1d()).isEqualByComparingTo("0.04");  // 5% - 1%
        assertThat(sig.announcementCar3d()).isEqualByComparingTo("0.04");  // d0+1,+2 contribute 0
        assertThat(sig.abnormalVolume()).isEqualByComparingTo("2");        // 2000 / 1000
        assertThat(sig.momentum6_12m()).isNull();                          // only 23 bars
        assertThat(sig.adv()).isNotNull();                                 // >=20 bars
    }

    @Test
    void betaAdjustsCar() {
        double[] s = flat(23, 100); s[19] = 100; s[20] = 105;
        long[] sv = flatVol(23, 1_000);
        double[] m = flat(23, 200); m[19] = 200; m[20] = 202;
        long[] mv = flatVol(23, 1);
        LocalDate report = START.plusDays(20);

        MarketSignals sig = svc.compute(bars(s, sv), bars(m, mv), report, 2.0);

        assertThat(sig.announcementCar1d()).isEqualByComparingTo("0.03"); // 5% - 2*1%
    }

    @Test
    void computesMomentumAndAdvOnLongSeries() {
        int n = 253;
        double[] s = flat(n, 100); s[0] = 100; s[126] = 110; // close[n-1-126]/close[n-1-252]-1 = s[126]/s[0]-1
        long[] sv = flatVol(n, 1_000);
        LocalDate report = START.plusDays(200);

        MarketSignals sig = svc.compute(bars(s, sv), bars(flat(n, 50), flatVol(n, 1)), report, null);

        assertThat(sig.momentum6_12m()).isEqualByComparingTo("0.10"); // 110/100 - 1
        assertThat(sig.adv()).isEqualByComparingTo("100000.00");      // last 20 bars: 100 * 1000
    }

    @Test
    void degradesOnEmptyAndMissingMarket() {
        assertThat(svc.compute(List.of(), null, LocalDate.now(), null)).isEqualTo(MarketSignals.empty());

        double[] s = flat(23, 100); s[19] = 100; s[20] = 105;
        MarketSignals noMarket = svc.compute(bars(s, flatVol(23, 1_000)), null, START.plusDays(20), null);
        assertThat(noMarket.carAvailable()).isFalse();
        assertThat(noMarket.announcementCar1d()).isNull();
        assertThat(noMarket.abnormalVolume()).isNotNull(); // volume still computable
    }
}
