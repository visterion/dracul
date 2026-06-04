package de.visterion.dracul.hunting.yahoo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Yahoo intraday candles (5-minute bars over the current day). Unofficial and
 * fragile, so every failure degrades to empty candles — the Daywalker poll must
 * never die on a Yahoo hiccup. Returns parallel close + volume lists.
 */
@Component
public class YahooIntradayAdapter {

    private static final Logger log = LoggerFactory.getLogger(YahooIntradayAdapter.class);
    private static final IntradayCandles EMPTY = new IntradayCandles(List.of(), List.of());

    private final RestClient client;

    public YahooIntradayAdapter(RestClient yahooRestClient) {
        this.client = yahooRestClient;
    }

    public IntradayCandles intradayCandles(String symbol) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                            .queryParam("range", "1d")
                            .queryParam("interval", "5m")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Yahoo intraday fetch failed for {}: {}", symbol, e.getMessage());
            return EMPTY;
        }
        if (body == null) return EMPTY;
        JsonNode result = body.path("chart").path("result");
        if (!result.isArray() || result.isEmpty() || result.get(0).isNull()) return EMPTY;
        JsonNode quote = result.get(0).path("indicators").path("quote").path(0);

        List<BigDecimal> closes = new ArrayList<>();
        for (JsonNode n : quote.path("close")) {
            if (!n.isNull()) closes.add(new BigDecimal(n.asText()));
        }
        List<Long> volumes = new ArrayList<>();
        for (JsonNode n : quote.path("volume")) {
            if (!n.isNull()) volumes.add(n.asLong());
        }
        return new IntradayCandles(closes, volumes);
    }
}
