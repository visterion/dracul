package de.visterion.dracul.watchlist;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Refreshes watchlist prices in the background so the watchlist read can serve fresh stored
 * values. Runs every minute during US market hours; quotes come from the Finnhub-first chain.
 * Symbols a provider cannot resolve keep their stored price (no overwrite). Never throws out of
 * the scheduled method — a failure must not kill the scheduler thread.
 */
@Component
@ConditionalOnProperty(value = "dracul.watchlist.price-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class WatchlistPriceRefresher {

    private static final Logger log = LoggerFactory.getLogger(WatchlistPriceRefresher.class);

    private final WatchlistRepository repo;
    private final AgoraMarketData marketData;

    public WatchlistPriceRefresher(WatchlistRepository repo, AgoraMarketData marketData) {
        this.repo = repo;
        this.marketData = marketData;
    }

    @Scheduled(cron = "${dracul.watchlist.price-refresh.cron:0 * 13-20 * * MON-FRI}", zone = "UTC")
    public void refresh() {
        try {
            List<String> tickers = repo.distinctTickers();
            if (tickers.isEmpty()) return;
            Map<String, Quote> quotes = marketData.quotes(tickers);
            int updated = 0;
            for (var e : quotes.entrySet()) {
                Quote q = e.getValue();
                updated += repo.updatePriceByTicker(
                        e.getKey(), q.price().doubleValue(), q.dayChangePercent().doubleValue());
            }
            log.debug("watchlist price refresh: {} ticker(s) requested, {} row(s) updated",
                    tickers.size(), updated);
        } catch (RuntimeException e) {
            log.warn("watchlist price refresh failed: {}", e.getMessage());
        }
    }
}
