package de.visterion.dracul.marketdata;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
        String currency = meta.path("currency").asText("USD");

        JsonNode closes = result.get(0).path("indicators")
                .path("quote").path(0).path("close");
        List<BigDecimal> history = new ArrayList<>();
        if (closes.isArray()) {
            for (JsonNode n : closes) {
                if (!n.isNull()) history.add(new BigDecimal(n.asText()));
            }
        }
        return new MarketData(name, price, BigDecimal.ZERO, currency, history);
    }

    /** Maps a requested trading-day count to the closest Yahoo Finance range string. */
    private static String yahooRange(int days) {
        if (days <= 63)  return "3mo";
        if (days <= 126) return "6mo";
        if (days <= 252) return "1y";
        if (days <= 504) return "2y";
        return "5y";
    }

    @Override
    public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
        String range = yahooRange(days);
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                            .queryParam("range", range)
                            .queryParam("interval", "1d")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo Finance OHLC history returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo Finance OHLC history unreachable: " + e.getMessage(), e);
        }

        if (body == null) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Empty body from Yahoo Finance OHLC history");
        }

        JsonNode result = body.path("chart").path("result");
        if (!result.isArray() || result.isEmpty() || result.get(0).isNull()) {
            throw new MarketDataException(
                    MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found in Yahoo OHLC history");
        }

        JsonNode r0 = result.get(0);
        JsonNode timestamps = r0.path("timestamp");
        JsonNode quote = r0.path("indicators").path("quote").path(0);
        JsonNode opens   = quote.path("open");
        JsonNode highs   = quote.path("high");
        JsonNode lows    = quote.path("low");
        JsonNode closes  = quote.path("close");
        JsonNode volumes = quote.path("volume");

        List<OhlcBar> out = new ArrayList<>();
        if (timestamps.isArray()) {
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode closeNode = closes.path(i);
                if (closeNode.isNull() || closeNode.isMissingNode()) continue;

                LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atOffset(ZoneOffset.UTC).toLocalDate();
                BigDecimal open   = bd(opens.path(i));
                BigDecimal high   = bd(highs.path(i));
                BigDecimal low    = bd(lows.path(i));
                BigDecimal close  = bd(closeNode);
                long volume = volumes.path(i).asLong(0);

                out.add(new OhlcBar(date, open, high, low, close, volume));
            }
        }
        // Yahoo returns oldest-first already — no reverse needed
        return out;
    }

    private static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asText("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
