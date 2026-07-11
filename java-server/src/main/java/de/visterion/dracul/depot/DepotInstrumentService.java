package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 * {@code symbol}). This service calls them with Agora's own default date window and returns the
 * raw, unfiltered output as-is; per-symbol filtering of those two sections, if wanted, is left
 * to the caller/GUI.
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
        JsonNode earnings = section("get_earnings_window", mapper.createObjectNode());
        JsonNode analystEstimates = section("get_analyst_estimates", symbolArgs(symbol));
        JsonNode earningsEstimates = section("get_earnings_estimates", symbolArgs(symbol));
        JsonNode fundamentalScore = section("get_fundamental_score", symbolArgs(symbol));
        JsonNode fundamentals = section("get_fundamentals", symbolArgs(symbol));
        JsonNode insiderActivity = section("get_form4_transactions", mapper.createObjectNode());

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
}
