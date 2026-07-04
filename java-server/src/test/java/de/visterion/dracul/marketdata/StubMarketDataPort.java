package de.visterion.dracul.marketdata;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StubMarketDataPort extends AgoraMarketData {

    private final Map<String, MarketData> entries = new HashMap<>();
    private boolean unavailable = false;

    public StubMarketDataPort() {
        super(null);
    }

    public StubMarketDataPort register(String symbol, String name, double price) {
        return register(symbol, name, price, "USD");
    }

    public StubMarketDataPort register(String symbol, String name, double price, String currency) {
        entries.put(symbol, new MarketData(
                name, BigDecimal.valueOf(price), BigDecimal.ZERO,
                currency, List.of(BigDecimal.valueOf(price))));
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

    @Override
    public Map<String, Quote> quotes(Collection<String> symbols) {
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

    @Override
    public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "OHLC history not supported by this stub", null);
    }
}
