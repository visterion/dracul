package de.visterion.dracul.marketdata;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete market-data facade backed by Agora (get_quote / get_ohlc over MCP). Replaces the old
 * MarketDataPort seam. Agora performs provider fallback internally, so no Dracul-side fallback.
 */
@Component
public class AgoraMarketData {

    private static final int HISTORY_DAYS = 30;
    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraMarketData(AgoraClient agora) { this.agora = agora; }

    /** Full snapshot: current quote + 30d close history. Throws MarketDataException on failure. */
    public MarketData resolve(String symbol) {
        JsonNode quoteRow;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.putArray("symbols").add(symbol);
            JsonNode out = agora.callTool("get_quote", args);
            JsonNode quotes = out.path("quotes");
            if (!quotes.isArray() || quotes.isEmpty()) {
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no quote for " + symbol, null);
            }
            quoteRow = quotes.get(0);
        } catch (AgoraUnavailableException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, e.getMessage(), e);
        }

        BigDecimal price = bd(quoteRow.path("price"));
        BigDecimal dayChange = bd(quoteRow.path("dayChangePercent"));
        String currency = quoteRow.path("currency").asString("USD");

        List<BigDecimal> history = new ArrayList<>();
        try {
            for (OhlcBar b : dailyOhlcHistory(symbol, HISTORY_DAYS)) history.add(b.close());
        } catch (MarketDataException ignored) {
            // history is best-effort; a quote without history is still usable
        }
        // companyName is not provided by Agora get_quote — use the symbol as a display fallback.
        return new MarketData(symbol, price, dayChange, currency, history);
    }

    /** Batch quote lookup in ONE get_quote call; symbols that don't resolve are omitted.
     *  Returns an empty map on Agora failure (caller keeps its stored price). */
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        ObjectNode args = mapper.createObjectNode();
        ArrayNode arr = args.putArray("symbols");
        for (String s : symbols) arr.add(s);
        JsonNode res;
        try {
            res = agora.callTool("get_quote", args);
        } catch (AgoraUnavailableException e) {
            return out; // degrade: caller keeps stored values
        }
        for (JsonNode q : res.path("quotes")) {
            String sym = q.path("symbol").asString("");
            if (sym.isEmpty()) continue;
            out.put(sym, new Quote(bd(q.path("price")), bd(q.path("dayChangePercent"))));
        }
        return out;
    }

    /** Daily OHLC history, oldest-first. Throws MarketDataException(UNAVAILABLE) on failure. */
    public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol).put("days", days);
            res = agora.callTool("get_ohlc", args);
        } catch (AgoraUnavailableException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, e.getMessage(), e);
        }
        List<OhlcBar> bars = new ArrayList<>();
        for (JsonNode b : res.path("bars")) {
            try {
                bars.add(new OhlcBar(
                        LocalDate.parse(b.path("date").asString()),
                        bd(b.path("open")), bd(b.path("high")), bd(b.path("low")), bd(b.path("close")),
                        b.path("volume").asLong(0)));
            } catch (RuntimeException ignored) { /* skip malformed bar */ }
        }
        return bars;
    }

    private static BigDecimal bd(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(n.asString("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
