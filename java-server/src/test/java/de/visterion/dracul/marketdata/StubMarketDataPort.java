package de.visterion.dracul.marketdata;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StubMarketDataPort implements MarketDataPort {

    private final Map<String, MarketData> entries = new HashMap<>();
    private boolean unavailable = false;

    public StubMarketDataPort register(String symbol, String name, double price) {
        entries.put(symbol, new MarketData(
                name, BigDecimal.valueOf(price),
                List.of(BigDecimal.valueOf(price))));
        return this;
    }

    public void forceUnavailable() { this.unavailable = true; }
    public void reset() { entries.clear(); unavailable = false; }

    @Override
    public MarketData resolve(String symbol) {
        if (unavailable) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE, "stub unavailable");
        }
        MarketData md = entries.get(symbol);
        if (md == null) {
            throw new MarketDataException(
                    MarketDataException.Kind.NOT_FOUND, "stub: " + symbol);
        }
        return md;
    }
}
