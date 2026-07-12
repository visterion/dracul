package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexDriftSnapshotterTest {

    private static final String SYM = "DRF";
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate ANN = START;              // announcement bar = bars[0]
    private static final LocalDate EFF = START.plusDays(5);  // effective bar = bars[5]
    private static final LocalDate TODAY = START.plusDays(10);

    private AgoraMarketData marketData;
    private IndexDriftSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        marketData = mock(AgoraMarketData.class);
        snapshotter = new IndexDriftSnapshotter(marketData);
    }

    /** 11 daily bars START+0..10 with the given closes (announcement=[0], effective=[5], latest=[10]). */
    private void stubBars(double announcementClose, double effectiveClose, double latestClose) {
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            double close = switch (i) {
                case 0 -> announcementClose;
                case 5 -> effectiveClose;
                case 10 -> latestClose;
                default -> announcementClose; // filler; the anchors are what matter
            };
            BigDecimal c = BigDecimal.valueOf(close);
            bars.add(new OhlcBar(START.plusDays(i), c, c, c, c, 1_000));
        }
        when(marketData.dailyOhlcHistory(anyString(), anyInt())).thenReturn(bars);
    }

    @Test void happyPathComputesRunUpDriftReversalAndDays() {
        stubBars(100, 110, 104.5);   // run-up +10%, post-effective 104.5/110-1 = -5%

        var s = snapshotter.snapshot(SYM, ANN, EFF, TODAY);

        assertThat(s.runUpPct()).isEqualTo(10.0);
        assertThat(s.postEffectivePct()).isEqualTo(-5.0);
        assertThat(s.reversalObserved()).isTrue();           // opposite signs, |−5%| > 1% noise floor
        assertThat(s.daysSinceEffective()).isEqualTo(5);
        assertThat(s.available()).isTrue();
    }

    @Test void continuationSameSignIsNotAReversal() {
        stubBars(100, 110, 115.5);   // run-up +10%, post-effective +5% (same direction)

        var s = snapshotter.snapshot(SYM, ANN, EFF, TODAY);

        assertThat(s.runUpPct()).isEqualTo(10.0);
        assertThat(s.postEffectivePct()).isEqualTo(5.0);
        assertThat(s.reversalObserved()).isFalse();
    }

    @Test void oppositeMoveWithinNoiseFloorIsNotAReversal() {
        stubBars(100, 110, 109.45);  // post-effective 109.45/110-1 = -0.5% (opposite but < 1% floor)

        var s = snapshotter.snapshot(SYM, ANN, EFF, TODAY);

        assertThat(s.postEffectivePct()).isEqualTo(-0.5);
        assertThat(s.reversalObserved()).isFalse();
    }

    @Test void priceSourceUnavailablePropagatesForTheBatchGuard() {
        when(marketData.dailyOhlcHistory(anyString(), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down"));

        assertThatThrownBy(() -> snapshotter.snapshot(SYM, ANN, EFF, TODAY))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void emptyBarsDegradeAllPriceFieldsWithoutThrow() {
        when(marketData.dailyOhlcHistory(anyString(), anyInt())).thenReturn(List.of());

        var s = snapshotter.snapshot(SYM, ANN, EFF, TODAY);

        assertThat(s.runUpPct()).isNull();
        assertThat(s.postEffectivePct()).isNull();
        assertThat(s.reversalObserved()).isNull();
        assertThat(s.daysSinceEffective()).isEqualTo(5);      // pure calendar, independent of prices
        assertThat(s.available()).isFalse();
    }
}
