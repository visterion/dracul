package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Index membership via Agora's {@code get_index_constituents} — the market-wide universe a
 * screening hunter needs when it must not depend on a manually maintained watchlist.
 *
 * <p>Only {@code sp500} is served upstream (Agora's {@code WikipediaService} raises
 * {@code UNAVAILABLE} for any other index name); the index is a parameter here anyway so an
 * operator can point Dracul at a second index the day Agora learns one, without a code change.
 *
 * <p><b>An empty constituent list is UNAVAILABLE, never a healthy empty universe.</b> That
 * distinction is the whole point of this class: strigoi-lazarus reported
 * {@code status=healthy, candidates=[]} on every run for weeks because its universe (the
 * watchlist) was empty and nothing in the fetch path could tell "nothing to screen" apart from
 * "nothing survived the screen". Agora itself refuses to cache a successful-but-empty index for
 * the same reason.
 */
@Component
public class AgoraIndexConstituents {

    private static final String SOURCE = "agora";

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraIndexConstituents(AgoraClient agora) {
        this.agora = agora;
    }

    /** Constituents of {@code index}; unavailable on any Agora failure AND on an empty list. */
    public DataSourceResult<IndexConstituent> constituents(String index) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("index", index);
            res = agora.callTool("get_index_constituents", args);
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable(SOURCE, "agora: " + e.getMessage());
        }
        List<IndexConstituent> out = new ArrayList<>();
        for (JsonNode c : res.path("constituents")) {
            String symbol = c.path("symbol").asString("").trim().toUpperCase(Locale.ROOT);
            if (symbol.isEmpty()) continue;
            out.add(new IndexConstituent(symbol, c.path("name").asString(""),
                    c.path("sector").asString("")));
        }
        if (out.isEmpty()) {
            return DataSourceResult.unavailable(SOURCE,
                    "agora: index " + index + " returned no constituents");
        }
        return DataSourceResult.healthy(SOURCE, out);
    }
}
