package de.visterion.dracul.marketdata;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class YahooMarketDataAdapter implements MarketDataPort {

    private final RestClient client;

    public YahooMarketDataAdapter(RestClient yahooRestClient) {
        this.client = yahooRestClient;
    }

    @Override
    public MarketData resolve(String symbol) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                            .queryParam("range", "1mo")
                            .queryParam("interval", "1d")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo Finance returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo Finance unreachable: " + e.getMessage(), e);
        }

        if (body == null) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Empty body from Yahoo Finance");
        }

        JsonNode result = body.path("chart").path("result");
        if (!result.isArray() || result.isEmpty() || result.get(0).isNull()) {
            throw new MarketDataException(
                    MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found");
        }

        JsonNode meta = result.get(0).path("meta");
        String name = meta.path("longName").asText(null);
        if (name == null || name.isBlank()) {
            name = meta.path("shortName").asText(symbol);
        }
        BigDecimal price = new BigDecimal(meta.path("regularMarketPrice").asText("0"));

        JsonNode closes = result.get(0).path("indicators")
                .path("quote").path(0).path("close");
        List<BigDecimal> history = new ArrayList<>();
        if (closes.isArray()) {
            for (JsonNode n : closes) {
                if (!n.isNull()) history.add(new BigDecimal(n.asText()));
            }
        }
        return new MarketData(name, price, history);
    }
}
