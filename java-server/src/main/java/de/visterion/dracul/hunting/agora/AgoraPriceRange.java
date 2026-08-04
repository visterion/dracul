package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/**
 * The CHEAP half of the quality-at-52-week-low screen: one {@code get_indicators} call with a
 * single {@code 52w_range} spec returns both the 52-week low and the current close of a symbol,
 * from ONE daily-OHLC fetch inside Agora (Yahoo-routed).
 *
 * <p>Why this exists at all: the authoritative screen reads its 52-week low out of
 * {@code get_fundamentals}, which routes US symbols to Finnhub — a source throttled to 60
 * calls/minute Agora-wide. Spending one of those on each of ~500 index members would rate-limit
 * the run and silently drop most of the universe, i.e. reproduce the very bug this pre-filter
 * exists to fix. So the universe is narrowed on this cheap signal first, and only the survivors
 * cost a fundamentals call.
 *
 * <p>This class deliberately does NOT swallow {@link de.visterion.dracul.marketdata.AgoraUnavailableException}:
 * the caller walks hundreds of symbols and needs an outage to be loud so it can stop rather than
 * burn a dead remote call per remaining symbol. Note that Agora reports "no history for this
 * symbol" through the SAME envelope, so a single exception is a per-symbol event — only a RUN of
 * them means the source is down (see {@code LazarusUniverseService}).
 */
@Component
public class AgoraPriceRange {

    private static final String LABEL = "52w_range";
    /** Bars the 52-week window must cover; matches Agora's own 252-bar 52w_range window. */
    private static final int MIN_BARS = 250;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraPriceRange(AgoraClient agora) {
        this.agora = agora;
    }

    /**
     * 52-week range + current close of {@code symbol}; null when Agora answered but the range is
     * not usable (indicator unavailable, no close, non-positive low).
     *
     * @throws de.visterion.dracul.marketdata.AgoraUnavailableException when the call itself failed
     */
    public PriceRange range52w(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        ArrayNode indicators = args.putArray("indicators");
        ObjectNode range = indicators.addObject();
        range.put("name", LABEL);
        range.putObject("params").put("minBars", MIN_BARS);

        JsonNode res = agora.callTool("get_indicators", args);

        BigDecimal close = bd(res.path("currentClose"));
        if (close == null || close.signum() <= 0) return null;

        for (JsonNode v : res.path("values")) {
            if (!LABEL.equals(v.path("label").asString(""))) continue;
            if (!v.path("available").asBoolean(false)) return null;
            BigDecimal low = bd(v.path("value").path("low"));
            BigDecimal high = bd(v.path("value").path("high"));
            if (low == null || low.signum() <= 0) return null;
            return new PriceRange(symbol, close, low, high);
        }
        return null;
    }

    private static BigDecimal bd(JsonNode v) {
        if (v.isMissingNode() || v.isNull()) return null;
        try {
            return new BigDecimal(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
