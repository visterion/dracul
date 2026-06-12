package de.visterion.dracul.marketdata;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Primary
public class TwelveDataMarketDataAdapter implements MarketDataPort {

    private final RestClient client;
    private final String apiKey;
    private final long ttlMillis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Quote quote, long expiresAt) {}

    public TwelveDataMarketDataAdapter(
            RestClient twelveDataRestClient,
            @Value("${dracul.marketdata.twelvedata.api-key:}") String apiKey,
            @Value("${dracul.marketdata.twelvedata.cache-seconds:120}") long cacheSeconds) {
        this.client = twelveDataRestClient;
        this.apiKey = apiKey;
        this.ttlMillis = cacheSeconds * 1000L;
    }

    @Override
    public MarketData resolve(String symbol) {
        JsonNode quote = getJson("/quote", symbol);
        requireOk(quote, symbol);
        String name = quote.path("name").asText(symbol);
        BigDecimal price = bd(quote, "close");
        BigDecimal change = bd(quote, "percent_change");

        JsonNode ts = getJson("/time_series", symbol);
        List<BigDecimal> history = new ArrayList<>();
        JsonNode values = ts.path("values");
        if (values.isArray()) {
            for (JsonNode v : values) {
                JsonNode c = v.path("close");
                if (!c.isMissingNode() && !c.isNull()) history.add(new BigDecimal(c.asText()));
            }
            Collections.reverse(history); // API is newest-first; MarketData expects oldest-first
        }
        return new MarketData(name, price, change, history);
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> symbols) {
        long now = System.currentTimeMillis();
        Map<String, Quote> out = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        for (String s : symbols) {
            Cached c = cache.get(s);
            if (c != null && c.expiresAt() > now) out.put(s, c.quote());
            else misses.add(s);
        }
        if (misses.isEmpty()) return out;

        JsonNode body = getJson("/quote", String.join(",", misses));
        for (String s : misses) {
            JsonNode node = misses.size() == 1 && !body.has(s) ? body : body.path(s);
            if (node.isMissingNode() || "error".equals(node.path("status").asText(""))) continue;
            Quote q = new Quote(bd(node, "close"), bd(node, "percent_change"));
            cache.put(s, new Cached(q, now + ttlMillis));
            out.put(s, q);
        }
        return out;
    }

    private JsonNode getJson(String path, String symbol) {
        try {
            return client.get()
                    .uri(uri -> uri.path(path)
                            .queryParam("symbol", symbol)
                            .queryParam("interval", "1day")
                            .queryParam("outputsize", 30)
                            .queryParam("apikey", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Twelve Data returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Twelve Data unreachable: " + e.getMessage(), e);
        }
    }

    private void requireOk(JsonNode node, String symbol) {
        if (node == null || "error".equals(node.path("status").asText(""))
                || node.path("close").isMissingNode()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found at Twelve Data");
        }
    }

    private static BigDecimal bd(JsonNode node, String field) {
        String v = node.path(field).asText("0");
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
