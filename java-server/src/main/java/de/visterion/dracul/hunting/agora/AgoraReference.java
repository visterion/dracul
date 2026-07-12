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
 * Reference-data facade backed by Agora (get_index_constituent_changes over MCP). Rows without a
 * symbol or without an effective date are skipped (the lifecycle natural key needs both). Never
 * throws: Agora failure degrades to an unavailable DataSourceResult.
 */
@Component
public class AgoraReference {

    private static final String SOURCE = "agora";

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraReference(AgoraClient agora) { this.agora = agora; }

    /**
     * Announced constituent changes (adds/removes) for an index within the lookback window,
     * via Agora ({@code get_index_constituent_changes}). Rows without a symbol or without an
     * effectiveDate are skipped (the lifecycle natural key needs both, and the effective date
     * anchors every downstream transition). Symbols are upper-cased to match the persisted
     * natural key. {@code companyName} is not carried by the tool (ticker-level changes only),
     * so it is passed through empty. {@code announcementDate} is parsed leniently (null when
     * absent). Never throws: Agora failure degrades to an unavailable DataSourceResult.
     */
    public DataSourceResult<IndexChangeEvent> indexChanges(String index, int lookbackDays) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("index", index);
            args.put("lookback_days", lookbackDays);
            res = agora.callTool("get_index_constituent_changes", args);
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable(SOURCE, "agora: " + e.getMessage());
        }
        List<IndexChangeEvent> out = new ArrayList<>();
        for (JsonNode c : res.path("changes")) {
            try {
                String symbol = c.path("symbol").asString("").toUpperCase(java.util.Locale.ROOT);
                JsonNode eff = c.path("effectiveDate");
                if (symbol.isEmpty() || eff.isMissingNode() || eff.isNull()) continue;
                LocalDate effective = LocalDate.parse(eff.asString());
                JsonNode ann = c.path("announcementDate");
                LocalDate announcement = (ann.isMissingNode() || ann.isNull() || ann.asString("").isEmpty())
                        ? null : LocalDate.parse(ann.asString());
                out.add(new IndexChangeEvent(
                        symbol,
                        c.path("companyName").asString(""),   // not carried by the Agora tool
                        c.path("index").asString(index),
                        c.path("action").asString(""),
                        announcement,
                        effective,
                        c.path("source").asString("")));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return DataSourceResult.healthy(SOURCE, out);
    }
}
