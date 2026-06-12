package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class FallbackMarketDataPortTest {

    /** Fake adapter: returns registered data; throws NOT_FOUND for unknown symbols; or always-fails. */
    static class FakePort implements MarketDataPort {
        final Map<String, MarketData> data = new HashMap<>();
        boolean alwaysFail = false;
        FakePort put(String s, double price, double change) {
            data.put(s, new MarketData(s + " Inc", BigDecimal.valueOf(price), BigDecimal.valueOf(change), List.of()));
            return this;
        }
        @Override public MarketData resolve(String symbol) {
            if (alwaysFail) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down");
            MarketData md = data.get(symbol);
            if (md == null) throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, symbol);
            return md;
        }
    }

    @Test void resolveUsesPrimaryWhenItSucceeds() {
        var primary = new FakePort().put("AAPL", 190.5, 1.3);
        var fallback = new FakePort().put("AAPL", 1.0, 0.0);
        var port = new FallbackMarketDataPort(primary, fallback);

        assertThat(port.resolve("AAPL").currentPrice()).isEqualByComparingTo("190.5");
    }

    @Test void resolveFallsBackWhenPrimaryFails() {
        var primary = new FakePort(); primary.alwaysFail = true;
        var fallback = new FakePort().put("AAPL", 188.0, 0.0);
        var port = new FallbackMarketDataPort(primary, fallback);

        assertThat(port.resolve("AAPL").currentPrice()).isEqualByComparingTo("188.0");
    }

    @Test void resolvePropagatesWhenBothFail() {
        var primary = new FakePort(); primary.alwaysFail = true;
        var fallback = new FakePort(); fallback.alwaysFail = true;
        var port = new FallbackMarketDataPort(primary, fallback);

        assertThatThrownBy(() -> port.resolve("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void quotesFillMissingSymbolsFromFallback() {
        // primary knows AAPL only; MSFT must come from fallback
        var primary = new FakePort().put("AAPL", 190.5, 1.3);
        var fallback = new FakePort().put("MSFT", 402.1, -0.3);
        var port = new FallbackMarketDataPort(primary, fallback);

        Map<String, Quote> q = port.quotes(List.of("AAPL", "MSFT"));

        assertThat(q.get("AAPL").price()).isEqualByComparingTo("190.5");
        assertThat(q.get("MSFT").price()).isEqualByComparingTo("402.1");
    }

    @Test void quotesFallBackEntirelyWhenPrimaryThrows() {
        var primary = new FakePort(); primary.alwaysFail = true; // batch HTTP failure
        var fallback = new FakePort().put("AAPL", 188.0, 0.0);
        var port = new FallbackMarketDataPort(primary, fallback);

        Map<String, Quote> q = port.quotes(List.of("AAPL"));

        assertThat(q.get("AAPL").price()).isEqualByComparingTo("188.0");
    }
}
