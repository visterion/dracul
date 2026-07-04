package de.visterion.dracul.stopguard;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.watchlist.PositionRisk;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic intraday watcher: every poll during the US session, classify
 *  each held position's live price against its persisted active stop and emit a
 *  STOP_PROXIMITY / STOP_BREACHED alert. No LLM; gated off by default; never
 *  throws out of the scheduled method. */
@Component
@ConditionalOnProperty(value = "dracul.stopguard.enabled", havingValue = "true")
public class StopProximityWatcher {

    private static final Logger log = LoggerFactory.getLogger(StopProximityWatcher.class);

    private final WatchlistRepository watchlist;
    private final AgoraMarketData marketData;
    private final StopAlertEmitter emitter;
    private final double atrMultiple;

    public StopProximityWatcher(WatchlistRepository watchlist, AgoraMarketData marketData,
            StopAlertEmitter emitter,
            @Value("${dracul.stopguard.atr-multiple:0.5}") double atrMultiple) {
        this.watchlist = watchlist;
        this.marketData = marketData;
        this.emitter = emitter;
        this.atrMultiple = atrMultiple;
    }

    @Scheduled(cron = "${dracul.stopguard.cron:0 */15 9-16 * * 1-5}", zone = "America/New_York")
    public void poll() {
        try {
            List<WatchlistItem> held = watchlist.findAll().stream()
                    .filter(it -> "HELD".equals(it.tag())
                            && it.entryPrice() != null && it.shareCount() != null)
                    .toList();
            if (held.isEmpty()) return;

            Map<String, PositionRisk> riskByItem = watchlist.positionRiskByItemId();

            Set<String> tickers = new LinkedHashSet<>();
            for (WatchlistItem it : held) {
                PositionRisk pr = riskByItem.get(it.id());
                if (pr != null && pr.activeStop() != null) tickers.add(it.ticker());
            }
            if (tickers.isEmpty()) return;

            Map<String, Quote> quotes;
            // AgoraMarketData.quotes() returns an empty map on Agora failure (never throws); this catch is defensive.
            try {
                quotes = marketData.quotes(tickers);
            } catch (Exception e) {
                log.warn("stopguard: price fetch failed for {} ticker(s) — skipping tick: {}",
                        tickers.size(), e.getMessage());
                return;
            }

            Instant now = Instant.now();
            for (WatchlistItem it : held) {
                PositionRisk pr = riskByItem.get(it.id());
                if (pr == null || pr.activeStop() == null) continue;
                Quote q = quotes.get(it.ticker());
                if (q == null || q.price() == null) continue;
                StopZone zone = StopZoneEvaluator.evaluate(
                        q.price(), pr.activeStop(), pr.atr(), atrMultiple);
                if (zone == StopZone.NONE) continue;
                emitter.emit(it.owner(), it.id(), it.ticker(), zone,
                        q.price(), pr.activeStop(), now);
            }
        } catch (RuntimeException e) {
            log.warn("stopguard poll failed", e);
        }
    }
}
