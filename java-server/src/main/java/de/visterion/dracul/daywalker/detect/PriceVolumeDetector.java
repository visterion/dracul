package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.agora.IntradayCandles;
import de.visterion.dracul.watchlist.WatchlistItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure threshold logic over intraday candles. Emits a PRICE_SPIKE when the last
 * close moved more than {@code priceThreshold} (fraction) versus ~one hour
 * earlier, and a VOLUME_SPIKE when the last bar's volume exceeds
 * {@code volumeMultiplier}x the average of the preceding bars.
 */
public class PriceVolumeDetector {

    /** Number of 5-minute bars that approximate a one-hour lookback. */
    private static final int ONE_HOUR_BARS = 12;

    public List<TriggerEvent> detect(WatchlistItem item, IntradayCandles candles,
                                     double priceThreshold, double volumeMultiplier) {
        var events = new ArrayList<TriggerEvent>();
        List<BigDecimal> closes = candles.closes();
        BigDecimal price = closes.isEmpty()
                ? BigDecimal.valueOf(item.currentPrice())
                : closes.get(closes.size() - 1);

        if (closes.size() >= 2) {
            int refIdx = Math.max(0, closes.size() - 1 - ONE_HOUR_BARS);
            BigDecimal ref = closes.get(refIdx);
            if (ref.signum() != 0) {
                double pct = price.subtract(ref).doubleValue() / ref.doubleValue();
                if (Math.abs(pct) > priceThreshold) {
                    events.add(TriggerEvent.watchOnly(item.ticker(), item.companyName(),
                            TriggerType.PRICE_SPIKE, price,
                            Map.of("price_change_pct", round(pct),
                                    "from_price", ref,
                                    "to_price", price)));
                }
            }
        }

        List<Long> vols = candles.volumes();
        if (vols.size() >= 2) {
            long lastVol = vols.get(vols.size() - 1);
            double avg = vols.subList(0, vols.size() - 1).stream()
                    .mapToLong(Long::longValue).average().orElse(0);
            if (avg > 0 && lastVol > volumeMultiplier * avg) {
                events.add(TriggerEvent.watchOnly(item.ticker(), item.companyName(),
                        TriggerType.VOLUME_SPIKE, price,
                        Map.of("volume_multiple", round(lastVol / avg),
                                "last_volume", lastVol,
                                "avg_volume", Math.round(avg))));
            }
        }
        return events;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
