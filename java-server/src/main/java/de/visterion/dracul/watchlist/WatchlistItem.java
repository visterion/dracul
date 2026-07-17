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
        String currency, String entryCurrency,
        Double nativeCurrentPrice, String nativeCurrency, Double nativeEntryPrice,
        String source) {

    /**
     * Back-compat constructor (pre-SP-2 16-arg shape). Used by internal detection call sites
     * (e.g. {@code DaywalkerEventEngine.asDetectorItem}) that build a throwaway item with no
     * watchlist row behind it, so provenance is irrelevant -- defaults {@code source} to
     * {@code "manual"}. Native fields mirror the values supplied, so an un-mapped
     * (un-converted) record is self-consistent: native price = currentPrice, native
     * currency = currency, native entry = entryPrice.
     */
    public WatchlistItem(
            String id, String ticker, String companyName,
            double currentPrice, double dayChangePercent,
            String status, String addedAt, String tag,
            String verdictId, List<WatchlistAlert> alerts,
            List<Double> priceHistory30d,
            Double entryPrice, Double shareCount,
            String owner,
            String currency, String entryCurrency) {
        this(id, ticker, companyName, currentPrice, dayChangePercent, status, addedAt, tag,
                verdictId, alerts, priceHistory30d, entryPrice, shareCount, owner,
                currency, entryCurrency,
                currentPrice, currency, entryPrice, "manual");
    }

    /**
     * 17-arg constructor (the 16-arg back-compat shape + {@code source}). Used by the
     * repository row mapper, where {@code source} is read straight from the DB column.
     * Native fields are derived exactly as the 16-arg ctor does.
     */
    public WatchlistItem(
            String id, String ticker, String companyName,
            double currentPrice, double dayChangePercent,
            String status, String addedAt, String tag,
            String verdictId, List<WatchlistAlert> alerts,
            List<Double> priceHistory30d,
            Double entryPrice, Double shareCount,
            String owner,
            String currency, String entryCurrency,
            String source) {
        this(id, ticker, companyName, currentPrice, dayChangePercent, status, addedAt, tag,
                verdictId, alerts, priceHistory30d, entryPrice, shareCount, owner,
                currency, entryCurrency,
                currentPrice, currency, entryPrice, source);
    }

    /**
     * Returns a copy with converted prices + the display currency code, preserving the native
     * original price/currency (read from this instance's pre-conversion state).
     */
    public WatchlistItem withConverted(BigDecimal currentPrice, BigDecimal entryPrice,
                                       String displayCurrency, String nativeCurrencyCode) {
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
                displayCurrency, entryCurrency,
                this.currentPrice, nativeCurrencyCode, this.entryPrice, this.source);
    }
}
