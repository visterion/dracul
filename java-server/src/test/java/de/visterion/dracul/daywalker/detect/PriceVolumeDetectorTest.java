package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.agora.IntradayCandles;
import de.visterion.dracul.watchlist.WatchlistItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceVolumeDetectorTest {

    private final PriceVolumeDetector detector = new PriceVolumeDetector();

    private static WatchlistItem item() {
        return new WatchlistItem("id-1", "ACME", "Acme Corp", 100.0, 0.0,
                "calm", "2026-06-01", "", null, List.of(), List.of(), null, null, null, null, null);
    }

    private static List<BigDecimal> closes(double... v) {
        return java.util.Arrays.stream(v).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    void firesPriceSpikeAboveThreshold() {
        var candles = new IntradayCandles(closes(100, 105), List.of()); // +5% > 3%
        var events = detector.detect(item(), candles, 0.03, 3.0);
        assertThat(events).extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.PRICE_SPIKE);
    }

    @Test
    void noPriceSpikeBelowThreshold() {
        var candles = new IntradayCandles(closes(100, 101), List.of()); // +1%
        assertThat(detector.detect(item(), candles, 0.03, 3.0)).isEmpty();
    }

    @Test
    void firesVolumeSpikeAboveMultiplier() {
        var candles = new IntradayCandles(closes(100, 100), List.of(1000L, 5000L)); // 5x > 3x
        assertThat(detector.detect(item(), candles, 0.03, 3.0))
                .extracting(TriggerEvent::triggerType)
                .containsExactly(TriggerType.VOLUME_SPIKE);
    }

    @Test
    void noVolumeSpikeBelowMultiplier() {
        var candles = new IntradayCandles(closes(100, 100), List.of(1000L, 2000L)); // 2x < 3x
        assertThat(detector.detect(item(), candles, 0.03, 3.0)).isEmpty();
    }
}
