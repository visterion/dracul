package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CHEAP half of the quality-at-52-week-low screen: one {@code 52w_range} spec returns both the
 * 52-week low and the current close of a symbol, from ONE daily-OHLC fetch inside Agora, served by
 * Agora's provider chain — Alpaca first for US symbols, then Saxo, TwelveData, Finnhub and Yahoo
 * as the last-resort fallback. (Until 2026-08-04 this javadoc named Yahoo as the route; it never
 * was one. Measured that day, 616 of the pre-filter's daily-bar fetches went to Alpaca.)
 *
 * <p>Two ways in, ONE classification. {@link #range52w(String)} asks {@code get_indicators} for a
 * single symbol; {@link #range52wBatch(List)} asks {@code get_indicators_batch} for a whole chunk
 * in one call. The batch route exists because one call per index member burned Alpaca's per-minute
 * quota: measured 2026-08-05, 49 of 645 Alpaca calls in the run window answered 429 and TwelveData
 * (8 credits/minute) tipped over immediately after, leaving Yahoo to carry the rest. Both routes
 * run the SAME {@link #classify} on the SAME per-symbol body, because a 52-week low that depends
 * on which route fetched it is worse than no pre-filter at all.
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
 * burn a dead remote call per remaining symbol. Since commit e5db10be an outage is the ONLY thing
 * that arrives as that exception — "no 52-week window for this symbol yet" comes back as a normal
 * body — so the three no-range reasons are separated in {@link RangeProbe} instead of collapsed
 * into one null (see {@code LazarusUniverseService}).
 */
@Component
public class AgoraPriceRange {

    private static final String LABEL = "52w_range";
    /** Bars the 52-week window must cover; matches Agora's own 252-bar 52w_range window. */
    private static final int MIN_BARS = 250;
    /**
     * Agora's own ceiling on {@code get_indicators_batch} ({@code GetIndicatorsBatchTool.MAX_SYMBOLS},
     * Agora commit 3200f3f). Over it the call is REJECTED, not truncated — deliberately, so a
     * half-screened index can never look like a fully screened one. Callers chunk well below this.
     */
    public static final int MAX_BATCH_SYMBOLS = 600;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraPriceRange(AgoraClient agora) {
        this.agora = agora;
    }

    /**
     * 52-week range + current close of {@code symbol}, or the reason there is none. Never null.
     *
     * @throws de.visterion.dracul.marketdata.AgoraUnavailableException when the call itself failed
     */
    public RangeProbe range52w(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        args.set("indicators", rangeSpec());

        return classify(symbol, agora.callTool("get_indicators", args));
    }

    /**
     * The same probe for a whole chunk of symbols in ONE {@code get_indicators_batch} call.
     *
     * <p>Every requested symbol is a key of the returned map — always, in the order asked for.
     * That is the whole point: a batch path that returns fewer symbols than it was given looks
     * exactly like a quiet market to everything downstream, so the gap is materialised as an
     * {@code UNUSABLE} probe the caller must count rather than as an absent key it can skip past.
     *
     * @param symbols at most {@link #MAX_BATCH_SYMBOLS}; the caller chunks
     * @throws IllegalArgumentException over that cap — Agora would reject the call outright, and
     *         a caller that has to be told so at runtime has a bug, not a degraded source
     * @throws de.visterion.dracul.marketdata.AgoraUnavailableException when the call itself failed;
     *         the caller then knows NOTHING about any symbol in the chunk, which is why this is
     *         not swallowed into a map of unusables here
     */
    public Map<String, RangeProbe> range52wBatch(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        if (symbols.size() > MAX_BATCH_SYMBOLS) {
            throw new IllegalArgumentException("get_indicators_batch takes at most "
                    + MAX_BATCH_SYMBOLS + " symbols, got " + symbols.size());
        }

        ObjectNode args = mapper.createObjectNode();
        ArrayNode syms = args.putArray("symbols");
        for (String s : symbols) syms.add(s);
        args.set("indicators", rangeSpec());

        JsonNode res = agora.callTool("get_indicators_batch", args);

        Map<String, JsonNode> bySymbol = new HashMap<>();
        for (JsonNode entry : res.path("results")) {
            String s = entry.path("symbol").asString("");
            if (!s.isEmpty()) bySymbol.put(s, entry);
        }

        Map<String, RangeProbe> out = new LinkedHashMap<>();
        for (String symbol : symbols) {
            JsonNode entry = bySymbol.get(symbol);
            // Absent from results altogether. Agora's batch tool contracts to carry an entry for
            // EVERY requested symbol — one with no bars comes back with available:false and a
            // reason — so a missing key means the answer itself is malformed: an upstream problem,
            // never a young instrument. Calling it NOT_ELIGIBLE here would put the BUG-S17
            // conflation back in through the other door, with the degradation silently uncounted.
            out.put(symbol, entry == null ? RangeProbe.unusable() : classify(symbol, entry));
        }
        return out;
    }

    /** The single indicator spec both routes ask for; identical arguments, identical answer. */
    private ArrayNode rangeSpec() {
        ArrayNode indicators = mapper.createArrayNode();
        ObjectNode range = indicators.addObject();
        range.put("name", LABEL);
        range.putObject("params").put("minBars", MIN_BARS);
        return indicators;
    }

    /**
     * The one classification, applied to one symbol's result object. {@code get_indicators} returns
     * that object at the top level; {@code get_indicators_batch} returns a list of exactly the same
     * objects. The order of the checks below is load-bearing — see the inline comment.
     */
    private static RangeProbe classify(String symbol, JsonNode body) {
        for (JsonNode v : body.path("values")) {
            if (!LABEL.equals(v.path("label").asString(""))) continue;
            // Checked BEFORE the close, deliberately: a symbol younger than the 250-bar window is
            // not-eligible whatever else the body does or does not carry, and that verdict must not
            // be shadowed by a missing close into a "degraded source" the operator then chases.
            if (!v.path("available").asBoolean(false)) return RangeProbe.notEligible();
            BigDecimal close = bd(body.path("currentClose"));
            if (close == null || close.signum() <= 0) return RangeProbe.unusable();
            BigDecimal low = bd(v.path("value").path("low"));
            BigDecimal high = bd(v.path("value").path("high"));
            if (low == null || low.signum() <= 0) return RangeProbe.unusable();
            return RangeProbe.of(new PriceRange(symbol, close, low, high));
        }
        // Agora answered without the very spec that was asked for — nothing about the instrument
        // explains that, so it is an upstream problem, not an ineligible symbol. This is also where
        // a batch symbol the Alpaca multi-symbol endpoint does not serve (non-US suffixes) lands:
        // the tool emits {symbol, available:false, error} with NO values array for it.
        return RangeProbe.unusable();
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
