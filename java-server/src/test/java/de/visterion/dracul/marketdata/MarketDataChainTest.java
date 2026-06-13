package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataChainTest {

    /** Stub that serves only the symbols it was given, throwing on resolve unless allowed. */
    static class StubPort implements MarketDataPort {
        final Map<String, Quote> data;
        final boolean resolveOk;
        StubPort(Map<String, Quote> data, boolean resolveOk) { this.data = data; this.resolveOk = resolveOk; }
        public MarketData resolve(String symbol) {
            if (!resolveOk || !data.containsKey(symbol))
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no");
            Quote q = data.get(symbol);
            return new MarketData(symbol, q.price(), q.dayChangePercent(), List.of());
        }
        public Map<String, Quote> quotes(Collection<String> symbols) {
            Map<String, Quote> out = new java.util.LinkedHashMap<>();
            for (String s : symbols) if (data.containsKey(s)) out.put(s, data.get(s));
            return out;
        }
    }

    private static Quote q(String p) { return new Quote(new BigDecimal(p), BigDecimal.ZERO); }

    @Test
    void quotesAreFilledAcrossAllThreeProviders() {
        var finnhub = new StubPort(Map.of("A", q("1")), false);
        var twelve  = new StubPort(Map.of("B", q("2")), true);
        var yahoo   = new StubPort(Map.of("C", q("3")), true);
        MarketDataPort chain = new FallbackMarketDataPort(finnhub, new FallbackMarketDataPort(twelve, yahoo));

        Map<String, Quote> out = chain.quotes(List.of("A", "B", "C"));
        assertThat(out.get("A").price()).isEqualByComparingTo("1"); // Finnhub
        assertThat(out.get("B").price()).isEqualByComparingTo("2"); // Twelve Data
        assertThat(out.get("C").price()).isEqualByComparingTo("3"); // Yahoo
    }

    @Test
    void resolveSkipsQuoteOnlyFinnhubAndUsesTwelveData() {
        var finnhub = new StubPort(Map.of(), false);       // resolve always throws
        var twelve  = new StubPort(Map.of("B", q("2")), true);
        var yahoo   = new StubPort(Map.of(), true);
        MarketDataPort chain = new FallbackMarketDataPort(finnhub, new FallbackMarketDataPort(twelve, yahoo));

        assertThat(chain.resolve("B").currentPrice()).isEqualByComparingTo("2");
    }
}
