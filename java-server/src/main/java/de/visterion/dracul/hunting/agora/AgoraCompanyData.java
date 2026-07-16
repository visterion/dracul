package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-company data facade backed by Agora (get_company_news / get_analyst_estimates /
 * get_fundamentals / get_company_profile over MCP). fundamentals/profile are returned as the
 * RAW provider blobs (opaque JsonNode) — key extraction is a consumer-side concern. Never
 * throws: Agora failure degrades to an empty list / null (the replaced adapters' contracts) —
 * except the health-aware variants ({@link #fundamentalsResult} and
 * {@link #recommendationsStrict}), which exist precisely so guard-carrying callers can tell
 * an Agora outage apart from "no data for this symbol".
 */
@Component
public class AgoraCompanyData {

    private static final Logger log = LoggerFactory.getLogger(AgoraCompanyData.class);
    private final AgoraClient agora;
    private final boolean includeSocial;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraCompanyData(AgoraClient agora,
                            @Value("${dracul.news.include-social:false}") boolean includeSocial) {
        this.agora = agora;
        this.includeSocial = includeSocial;
    }

    /**
     * Company news in [from, to]; empty list on any failure. Rows are parsed defensively
     * (no exception-based skipping); items without a parseable datetime are DROPPED here —
     * dateless items pass every Agora date window, and one such item would otherwise be a
     * perpetual NEGATIVE_NEWS trigger via NewsDetector after every cooldown expiry. When
     * dracul.news.include-social=false (default), the call asks Agora for sourceTypes
     * ["news"] (server-side, before Agora's item cap) AND drops sourceType=social items
     * client-side (defense against an old Agora that ignores the param); when true, the
     * param is omitted and nothing is filtered. Dateless/social items stay visible through
     * the raw tool and the depot passthrough (DepotInstrumentService).
     */
    public List<NewsHeadline> news(String symbol, LocalDate from, LocalDate to) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol).put("from", from.toString()).put("to", to.toString());
            if (!includeSocial) {
                args.putArray("sourceTypes").add("news");
            }
            res = agora.callTool("get_company_news", args);
        } catch (AgoraUnavailableException e) {
            return List.of();
        }
        List<NewsHeadline> out = new ArrayList<>();
        int droppedDateless = 0;
        int filteredSocial = 0;
        for (JsonNode n : res.path("news")) {
            String headline = n.path("headline").asString("");
            if (headline.isBlank()) continue;
            String sourceType = n.path("sourceType").asString("news");
            if (!includeSocial && "social".equalsIgnoreCase(sourceType)) {
                filteredSocial++;
                continue;
            }
            Instant datetime = parseDatetime(n.path("datetime").asString(""));
            if (datetime == null) {
                droppedDateless++;
                continue;
            }
            out.add(new NewsHeadline(
                    headline,
                    n.path("summary").asString(""),
                    n.path("source").asString(""),
                    sourceType,
                    datetime,
                    n.path("url").asString("")));
        }
        if (filteredSocial > 0) {
            log.debug("news: filtered {} social items for {}", filteredSocial, symbol);
        }
        if (droppedDateless > 0) {
            log.debug("news: dropped {} dateless items for {}", droppedDateless, symbol);
        }
        return out;
    }

    /** Defensive ISO-8601 parse; null for missing/blank/unparseable values (caller drops those items). */
    private static Instant parseDatetime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Analyst recommendation trend, newest-first as delivered; empty list on any failure
     *  (an Agora outage is swallowed — the replaced adapter's contract, kept for echo). */
    public List<RecommendationTrend> recommendations(String symbol) {
        try {
            return recommendationsStrict(symbol);
        } catch (AgoraUnavailableException e) {
            return List.of();
        }
    }

    /**
     * Strict variant of {@link #recommendations(String)}: propagates
     * {@link AgoraUnavailableException} instead of degrading to an empty list, so callers
     * with a per-batch source-down guard (lazarus/insider enrichment) can tell "Agora is
     * down" apart from "no recommendations for this symbol" and stop burning a dead ~16s
     * remote call per remaining candidate. Same split as {@code AgoraFilings}'
     * {@code fundamentalScoreStrict} vs the swallowing default.
     */
    public List<RecommendationTrend> recommendationsStrict(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        JsonNode res = agora.callTool("get_analyst_estimates", args);
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

    /**
     * Health-aware variant of {@link #fundamentals(String)}: tells "Agora is unreachable" apart
     * from "no data for this symbol", which the raw method collapses to null. Used by callers
     * (currently strigoi-lazarus) that need to surface a real outage as unavailable rather than
     * silently degrading; {@link #fundamentals(String)} keeps its existing null-on-any-failure
     * contract for other consumers (e.g. the echo hunter).
     */
    public DataSourceResult<JsonNode> fundamentalsResult(String symbol) {
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol);
            JsonNode m = agora.callTool("get_fundamentals", args).path("metrics");
            JsonNode value = (m.isMissingNode() || m.isNull()) ? null : m;
            return DataSourceResult.healthy("agora", value == null ? List.of() : List.of(value));
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable("agora", "agora: " + e.getMessage());
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
