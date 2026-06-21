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

    private final RestClient client;
    private final ConcurrentHashMap<String, BigDecimal> cache = new ConcurrentHashMap<>();

    public FxService(RestClient yahooRestClient) {
        this.client = yahooRestClient;
    }

    /** Convert from -> to using only cached rates; never performs a live fetch.
     *  Identity on null amount, same currency, or a cache miss. */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) return null;
        if (from == null || to == null || from.equalsIgnoreCase(to)) return amount;
        BigDecimal rate = cache.get(pair(from, to));
        if (rate == null) return amount; // not yet warmed: serve unconverted rather than block
        return amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    /** Fetch the latest from -> to rate and cache it. Called by the background refresher.
     *  Never throws; on failure logs and keeps the last-known rate. */
    public void warm(String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        String pair = pair(from, to);
        try {
            JsonNode body = client.get()
                    .uri("/v8/finance/chart/" + pair)
                    .retrieve().body(JsonNode.class);
            String px = body.path("chart").path("result").path(0).path("meta")
                    .path("regularMarketPrice").asText(null);
            if (px == null) throw new IllegalStateException("no regularMarketPrice for " + pair);
            cache.put(pair, new BigDecimal(px));
        } catch (Exception e) {
            log.warn("FX warm failed for {}: {} — keeping last-known", pair, e.toString());
        }
    }

    private static String pair(String from, String to) {
        return from.toUpperCase() + to.toUpperCase() + "=X";
    }
}
