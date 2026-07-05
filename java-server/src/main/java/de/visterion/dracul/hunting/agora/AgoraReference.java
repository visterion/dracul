package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference-data facade backed by Agora (get_index_constituents over MCP). Rows without a
 * symbol or without a dateAdded are skipped (consumers key on the addition date). Never
 * throws: Agora failure degrades to an unavailable DataSourceResult.
 */
@Component
public class AgoraReference {

    private static final String SOURCE = "agora";

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraReference(AgoraClient agora) { this.agora = agora; }

    /** Current S&P 500 constituents with their index-addition dates. */
    public DataSourceResult<Sp500Constituent> constituents() {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("index", "sp500");
            res = agora.callTool("get_index_constituents", args);
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable(SOURCE, "agora: " + e.getMessage());
        }
        List<Sp500Constituent> out = new ArrayList<>();
        for (JsonNode c : res.path("constituents")) {
            try {
                String symbol = c.path("symbol").asString("");
                JsonNode added = c.path("dateAdded");
                if (symbol.isEmpty() || added.isMissingNode() || added.isNull()) continue;
                out.add(new Sp500Constituent(symbol, c.path("name").asString(""),
                        LocalDate.parse(added.asString())));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return DataSourceResult.healthy(SOURCE, out);
    }
}
