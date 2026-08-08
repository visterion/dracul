package de.visterion.dracul.marketdata;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin pass-through to Agora's search_instruments. No provider code in Dracul — AgoraMarketData
 * deliberately replaced the direct-provider seam.
 */
@Service
public class InstrumentSearchService {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_LIMIT = 25;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public InstrumentSearchService(AgoraClient agora) {
        this.agora = agora;
    }

    /** Empty list for a too-short query, without spending an Agora call (and its shared lock). */
    public List<InstrumentSearchHit> search(String q, int limit) {
        String query = q == null ? "" : q.trim();
        if (query.length() < MIN_QUERY_LENGTH) return List.of();

        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        args.put("limit", Math.clamp(limit, 1, MAX_LIMIT));

        JsonNode out;
        try {
            out = agora.callTool("search_instruments", args);
        } catch (AgoraUnavailableException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, e.getMessage(), e);
        }
        List<InstrumentSearchHit> hits = new ArrayList<>();
        for (JsonNode r : out.path("results")) {
            String symbol = r.path("symbol").asString("");
            if (symbol.isEmpty()) continue;
            hits.add(new InstrumentSearchHit(
                    symbol,
                    r.path("name").asString(symbol),
                    r.path("exchange").asString(""),
                    r.path("type").asString("")));
        }
        return hits;
    }
}
