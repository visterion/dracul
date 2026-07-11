package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;

/**
 * Instrument info bundle for the GUI's Trade-Republic-style instrument page: profile, news,
 * earnings window, analyst estimates, earnings estimates, fundamental score, fundamentals, and
 * insider (Form 4) activity. Each section is fetched independently from Agora and returned as
 * its raw output {@link JsonNode} — {@code null} when that one call failed, so a single flaky
 * tool never takes down the whole bundle. Calls are SEQUENTIAL: {@link AgoraClient#callTool} is
 * {@code synchronized}, so parallelizing them buys nothing.
 *
 * <p>Two of the eight Agora tools — {@code get_earnings_window} and
 * {@code get_form4_transactions} — take no {@code symbol} input; they are market-wide,
 * date-windowed queries that return one row per company (each row carries its own
 * {@code symbol}/{@code ticker}). This service calls them with Agora's own default date window
 * and then filters the returned rows down to the requested instrument server-side — see
 * {@link #filterRowsBySymbol(JsonNode, String, String, String)} — so callers only ever see rows
 * for {@code symbol}.
 */
@Service
public class DepotInstrumentService {

    private static final Logger log = LoggerFactory.getLogger(DepotInstrumentService.class);
    private static final int NEWS_LOOKBACK_DAYS = 14;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public DepotInstrumentService(AgoraClient agora) {
        this.agora = agora;
    }

    /** One section per Agora tool; each nullable independently of the others. */
    public record InstrumentBundle(
            String symbol,
            JsonNode profile,
            JsonNode news,
            JsonNode earnings,
            JsonNode analystEstimates,
            JsonNode earningsEstimates,
            JsonNode fundamentalScore,
            JsonNode fundamentals,
            JsonNode insiderActivity) {
    }

    public InstrumentBundle bundle(String symbol) {
        JsonNode profile = section("get_company_profile", symbolArgs(symbol));
        JsonNode news = section("get_company_news", newsArgs(symbol));
        JsonNode earnings = filterRowsBySymbol(
                section("get_earnings_window", mapper.createObjectNode()), "earnings", "symbol", symbol);
        JsonNode analystEstimates = section("get_analyst_estimates", symbolArgs(symbol));
        JsonNode earningsEstimates = section("get_earnings_estimates", symbolArgs(symbol));
        JsonNode fundamentalScore = section("get_fundamental_score", symbolArgs(symbol));
        JsonNode fundamentals = section("get_fundamentals", symbolArgs(symbol));
        JsonNode insiderActivity = filterRowsBySymbol(
                section("get_form4_transactions", mapper.createObjectNode()), "transactions", "ticker", symbol);

        return new InstrumentBundle(symbol, profile, news, earnings, analystEstimates,
                earningsEstimates, fundamentalScore, fundamentals, insiderActivity);
    }

    private ObjectNode symbolArgs(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        return args;
    }

    private ObjectNode newsArgs(String symbol) {
        ObjectNode args = symbolArgs(symbol);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(NEWS_LOOKBACK_DAYS);
        args.put("from", from.toString());
        args.put("to", to.toString());
        return args;
    }

    /** One tool call, contained: a failure logs a warning and yields {@code null} for that section. */
    private JsonNode section(String tool, JsonNode args) {
        try {
            return agora.callTool(tool, args);
        } catch (RuntimeException e) {
            log.warn("Agora call {} failed for instrument bundle: {}", tool, e.toString());
            return null;
        }
    }

    /**
     * Post-filters a market-wide section's payload down to the requested instrument. {@code
     * get_earnings_window} and {@code get_form4_transactions} return every company in their date
     * window (field {@code arrayField}: {@code "earnings"} rows carry {@code "symbol"},
     * {@code "transactions"} rows carry {@code "ticker"}); this keeps only the rows whose {@code
     * rowField} matches {@code symbol} (case-insensitive), replacing the array in place while
     * preserving the rest of the envelope (e.g. {@code truncated}, {@code note}). {@code payload}
     * may be {@code null} (a failed call — passed through as {@code null}), and if the expected
     * array isn't present the payload is returned unchanged rather than filtered/nulled.
     */
    private JsonNode filterRowsBySymbol(JsonNode payload, String arrayField, String rowField, String symbol) {
        if (payload == null || !payload.isObject() || !payload.path(arrayField).isArray()) {
            return payload;
        }
        ObjectNode out = ((ObjectNode) payload).deepCopy();
        ArrayNode filtered = out.putArray(arrayField);
        for (JsonNode row : payload.path(arrayField)) {
            if (symbol.equalsIgnoreCase(row.path(rowField).asString())) {
                filtered.add(row);
            }
        }
        return out;
    }
}
