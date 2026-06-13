package de.visterion.dracul.marketdata;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quote-only market-data provider over Finnhub /quote (free tier 60 calls/min). Implements
 * quotes() for the high-volume watchlist refresh; resolve() deliberately throws so the
 * FallbackMarketDataPort chain routes history-bearing resolve() calls (add-symbol, screeners,
 * verdict synthesis) to Twelve Data instead — Finnhub's free tier has no cheap 30-day history.
 */
@Component
public class FinnhubMarketDataAdapter implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(FinnhubMarketDataAdapter.class);

    private final RestClient http;
    private final String apiKey;

    @Autowired
    public FinnhubMarketDataAdapter(
            @Value("${dracul.finnhub.api-key:}") String apiKey,
            @Value("${dracul.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Test constructor: pre-built RestClient + explicit key.
    FinnhubMarketDataAdapter(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    @Override
    public MarketData resolve(String symbol) {
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "Finnhub adapter is quote-only (no 30d history); use quotes()");
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        if (apiKey.isBlank()) return out; // chain falls back to Twelve Data
        for (String symbol : symbols) {
            try {
                JsonNode q = http.get()
                        .uri(uri -> uri.path("/quote")
                                .queryParam("symbol", symbol)
                                .queryParam("token", apiKey)
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                if (q == null) continue;
                BigDecimal price = bd(q, "c");
                if (price.signum() == 0) continue; // 0 = unknown symbol / no data → omit
                out.put(symbol, new Quote(price, bd(q, "dp")));
            } catch (Exception e) {
                log.warn("Finnhub quote fetch failed for {}: {}", symbol, e.getMessage());
                // omit — FallbackMarketDataPort fills this symbol from the next provider
            }
        }
        return out;
    }

    private static BigDecimal bd(JsonNode node, String field) {
        JsonNode f = node.path(field);
        if (f.isMissingNode() || f.isNull()) return BigDecimal.ZERO;
        String v = f.asText("0");
        if (v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
