package de.visterion.dracul.watchlist;

import java.util.List;

public record WatchlistItem(
        String id, String ticker, String companyName,
        double currentPrice, double dayChangePercent,
        String status, String addedAt, String tag,
        String verdictId, List<WatchlistAlert> alerts,
        List<Double> priceHistory30d,
        Double entryPrice, Double shareCount) {}
