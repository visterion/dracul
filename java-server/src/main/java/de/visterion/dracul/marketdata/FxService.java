package de.visterion.dracul.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);
    private static final long TTL_NANOS = 3600L * 1_000_000_000L; // 1h

    private final RestClient client;
    private record Cached(BigDecimal rate, long expiresAt) {}
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public FxService(RestClient yahooRestClient) {
        this.client = yahooRestClient;
    }

    /** Convert amount from -> to at the current rate; identity on same currency or on failure. */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) return null;
        if (from == null || to == null || from.equalsIgnoreCase(to)) return amount;
        BigDecimal rate = rate(from.toUpperCase(), to.toUpperCase());
        if (rate == null) return amount; // graceful: unconverted rather than crash
        return amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(String from, String to) {
        String pair = from + to + "=X";
        long now = System.nanoTime();
        Cached c = cache.get(pair);
        if (c != null && now < c.expiresAt()) return c.rate();
        try {
            JsonNode body = client.get()
                    .uri("/v8/finance/chart/" + pair)
                    .retrieve().body(JsonNode.class);
            JsonNode meta = body.path("chart").path("result").path(0).path("meta");
            String px = meta.path("regularMarketPrice").asText(null);
            if (px == null) throw new IllegalStateException("no regularMarketPrice for " + pair);
            BigDecimal rate = new BigDecimal(px);
            cache.put(pair, new Cached(rate, now + TTL_NANOS));
            return rate;
        } catch (Exception e) {
            log.warn("FX rate fetch failed for {}: {} — leaving amount unconverted", pair, e.toString());
            return c != null ? c.rate() : null; // last-known if any, else null (identity)
        }
    }
}
