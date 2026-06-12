package de.visterion.dracul.marketdata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public interface MarketDataPort {
    MarketData resolve(String symbol);

    /**
     * Batch price+day-change lookup for the on-read watchlist refresh. Default impl
     * resolves each symbol individually; adapters with a batch endpoint override this.
     * Symbols that fail to resolve are omitted from the result (caller keeps stored value).
     */
    default Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        for (String s : symbols) {
            try {
                MarketData md = resolve(s);
                out.put(s, new Quote(md.currentPrice(), md.dayChangePercent()));
            } catch (MarketDataException e) {
                // omit — caller falls back to the stored price
            }
        }
        return out;
    }
}
