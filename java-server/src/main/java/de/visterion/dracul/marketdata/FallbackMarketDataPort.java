package de.visterion.dracul.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Primary MarketDataPort: serves from Twelve Data, falling back to Yahoo when Twelve Data
 * fails (HTTP error, exhausted credits) or cannot resolve a symbol. Keeps add-symbol,
 * screeners, and verdict synthesis working when the primary provider is unavailable.
 */
public class FallbackMarketDataPort implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(FallbackMarketDataPort.class);

    private final MarketDataPort primary;
    private final MarketDataPort fallback;

    public FallbackMarketDataPort(MarketDataPort primary, MarketDataPort fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public MarketData resolve(String symbol) {
        try {
            return primary.resolve(symbol);
        } catch (RuntimeException e) {
            log.warn("Primary market data failed for {} ({}); falling back to Yahoo", symbol, e.toString());
            return fallback.resolve(symbol);
        }
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> result = new LinkedHashMap<>();
        try {
            result.putAll(primary.quotes(symbols));
        } catch (RuntimeException e) {
            log.warn("Primary batch quotes failed ({}); falling back to Yahoo for all", e.toString());
        }
        List<String> missing = new ArrayList<>();
        for (String s : symbols) if (!result.containsKey(s)) missing.add(s);
        if (!missing.isEmpty()) {
            try {
                result.putAll(fallback.quotes(missing));
            } catch (RuntimeException e) {
                log.warn("Fallback batch quotes failed for {}: {}", missing, e.toString());
            }
        }
        return result;
    }
}
