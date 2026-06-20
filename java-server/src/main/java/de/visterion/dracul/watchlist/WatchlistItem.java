package de.visterion.dracul.watchlist;

import java.math.BigDecimal;
import java.util.List;

public record WatchlistItem(
        String id, String ticker, String companyName,
        double currentPrice, double dayChangePercent,
        String status, String addedAt, String tag,
        String verdictId, List<WatchlistAlert> alerts,
        List<Double> priceHistory30d,
        Double entryPrice, Double shareCount,
        String owner,
        String currency, String entryCurrency) {

    /** Returns a copy with converted prices and the display currency code. */
    public WatchlistItem withConverted(BigDecimal currentPrice, BigDecimal entryPrice, String currency) {
        return new WatchlistItem(
                id, ticker, companyName,
                currentPrice == null ? 0.0 : currentPrice.doubleValue(),
                dayChangePercent,
                status, addedAt, tag,
                verdictId, alerts,
                priceHistory30d,
                entryPrice == null ? null : entryPrice.doubleValue(),
                shareCount,
                owner,
                currency, entryCurrency);
    }
}
