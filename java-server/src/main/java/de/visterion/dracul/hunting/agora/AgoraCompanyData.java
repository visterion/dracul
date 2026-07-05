package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.finnhub.NewsHeadline;
import de.visterion.dracul.hunting.finnhub.RecommendationTrend;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-company data facade backed by Agora (get_company_news / get_analyst_estimates /
 * get_fundamentals / get_company_profile over MCP). fundamentals/profile are returned as the
 * RAW provider blobs (opaque JsonNode) — key extraction is a consumer-side concern. Never
 * throws: Agora failure degrades to an empty list / null (the replaced adapters' contracts).
 */
@Component
public class AgoraCompanyData {

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraCompanyData(AgoraClient agora) { this.agora = agora; }

    /** Company news in [from, to]; empty list on any failure. */
    public List<NewsHeadline> news(String symbol, LocalDate from, LocalDate to) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol).put("from", from.toString()).put("to", to.toString());
            res = agora.callTool("get_company_news", args);
        } catch (AgoraUnavailableException e) {
            return List.of();
        }
        List<NewsHeadline> out = new ArrayList<>();
        for (JsonNode n : res.path("news")) {
            try {
                String headline = n.path("headline").asString("");
                if (headline.isBlank()) continue;
                out.add(new NewsHeadline(
                        headline,
                        n.path("summary").asString(""),
                        n.path("source").asString(""),
                        Instant.parse(n.path("datetime").asString()),
                        n.path("url").asString("")));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return out;
    }

    /** Analyst recommendation trend, newest-first as delivered; empty list on any failure. */
    public List<RecommendationTrend> recommendations(String symbol) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol);
            res = agora.callTool("get_analyst_estimates", args);
        } catch (AgoraUnavailableException e) {
            return List.of();
        }
        List<RecommendationTrend> out = new ArrayList<>();
        for (JsonNode n : res.path("recommendations")) {
            try {
                out.add(new RecommendationTrend(
                        n.path("period").asString(""),
                        n.path("strongBuy").asInt(0),
                        n.path("buy").asInt(0),
                        n.path("hold").asInt(0),
                        n.path("sell").asInt(0),
                        n.path("strongSell").asInt(0)));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return out;
    }

    /** RAW provider metrics blob (opaque); null when unavailable or absent. */
    public JsonNode fundamentals(String symbol) {
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol);
            JsonNode m = agora.callTool("get_fundamentals", args).path("metrics");
            return (m.isMissingNode() || m.isNull()) ? null : m;
        } catch (AgoraUnavailableException e) {
            return null;
        }
    }

    /** RAW provider profile blob (opaque); null when unavailable or absent. */
    public JsonNode profile(String symbol) {
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol);
            JsonNode p = agora.callTool("get_company_profile", args).path("profile");
            return (p.isMissingNode() || p.isNull()) ? null : p;
        } catch (AgoraUnavailableException e) {
            return null;
        }
    }
}
