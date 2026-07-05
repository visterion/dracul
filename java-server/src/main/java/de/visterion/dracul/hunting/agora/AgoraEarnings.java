package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.echo.EarningsObservation;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Earnings facade backed by Agora (get_earnings_window / get_earnings_calendar over MCP).
 * Agora performs the market-wide provider fallback internally, so no Dracul-side routing.
 * CRITICAL: both tools OMIT null fields (no JSON nulls) — bdOrNull handles missing and null
 * identically. Never throws: failure degrades to unavailable health / Optional.empty().
 */
@Component
public class AgoraEarnings {

    private static final String SOURCE = "agora";
    private static final int FORWARD_DAYS = 90;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraEarnings(AgoraClient agora) { this.agora = agora; }

    /** Market-wide earnings announcements with report date in [from, to]. */
    public DataSourceResult<EarningsObservation> recent(LocalDate from, LocalDate to) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("from", from.toString()).put("to", to.toString());
            res = agora.callTool("get_earnings_window", args);
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable(SOURCE, "agora: " + e.getMessage());
        }
        List<EarningsObservation> out = new ArrayList<>();
        for (JsonNode row : res.path("earnings")) {
            try {
                String symbol = row.path("symbol").asString("").toUpperCase();
                if (symbol.isEmpty()) continue;
                JsonNode date = row.path("date");
                if (date.isMissingNode() || date.isNull()) continue;  // date OMITTED when unknown
                out.add(new EarningsObservation(
                        symbol, symbol,                                // window tool has no company name
                        LocalDate.parse(date.asString()),
                        bdOrNull(row.path("epsActual")),
                        bdOrNull(row.path("epsEstimate")),
                        bdOrNull(row.path("epsSurprisePct")),
                        bdOrNull(row.path("revenueActual")),
                        bdOrNull(row.path("revenueEstimate"))));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return DataSourceResult.healthy(SOURCE, out);
    }

    /** Earliest scheduled earnings date strictly after today (90d forward window); empty if unknown. */
    public Optional<LocalDate> nextEarningsDate(String symbol) {
        LocalDate today = LocalDate.now();
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol)
                    .put("from", today.toString())
                    .put("to", today.plusDays(FORWARD_DAYS).toString());
            res = agora.callTool("get_earnings_calendar", args);
        } catch (AgoraUnavailableException e) {
            return Optional.empty();
        }
        LocalDate best = null;
        for (JsonNode row : res.path("earnings")) {
            try {
                JsonNode date = row.path("date");
                if (date.isMissingNode() || date.isNull()) continue;
                LocalDate d = LocalDate.parse(date.asString());
                if (d.isAfter(today) && (best == null || d.isBefore(best))) best = d;
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return Optional.ofNullable(best);
    }

    private static BigDecimal bdOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        try { return new BigDecimal(n.asString()); } catch (NumberFormatException e) { return null; }
    }
}
