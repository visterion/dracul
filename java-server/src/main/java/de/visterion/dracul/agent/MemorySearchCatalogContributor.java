package de.visterion.dracul.agent;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Single, unconditional contributor of the HiveMem "search" mcp catalog entry (T1.6 D1).
 *  Deliberately NOT one of the 8 @ConditionalOnProperty *Defaults beans — see spec §5.4 for
 *  why a conditional contributor creates a partial-enable hazard. */
@Component
class MemorySearchCatalogContributor implements ToolCatalogContributor {
    private static final String NAME = "search";

    private final ToolCatalogEntry entry;

    MemorySearchCatalogContributor(ObjectMapper mapper) {
        // The `where` object MUST be declared property-by-property (fix D9). While it was
        // advertised as a bare {"type":"object"}, the model invented where.symbol — HiveMem
        // rewrote/ignored it and every agent silently browsed the newest cells of the whole
        // realm instead of the ticker's own history (agent strigoi-index, 2026-08).
        //
        // Keys mirror HiveMem's CellSelector allow-list
        // (realm, realm_in, signal, topic, tags, query, status) MINUS:
        //   - query   — SearchToolHandler rejects where.query for the `search` tool
        //               ("where.query is not supported on search; use the top-level query").
        //   - realm_in — mutually exclusive with `realm` in CellSelector, and this token is
        //               confined to the single realm dracul-research, so it can only ever
        //               produce an error here. Deliberately not advertised.
        // Dracul writes the ticker as the cell `topic` (HiveMemResearchService.writeThesisMemory /
        // writeOutcomeCell), so `topic` is THE per-symbol filter. topic matches exactly;
        // tags match by overlap (Postgres `tags && ?`).
        JsonNode input = AgentResources.parseJson(mapper, """
                {"type":"object","properties":{
                  "where":{
                    "type":"object",
                    "description":"Filter for the memory lookup. These are the ONLY supported keys — any other key (in particular \\"symbol\\", which does not exist) makes the call fail with 'Unknown where field'. The ticker lives in \\"topic\\".",
                    "properties":{
                      "realm":{"type":"string","description":"Always \\"dracul-research\\" — the only realm authorized for this token; naming another one fails your run."},
                      "topic":{"type":"string","description":"The ticker symbol, matched EXACTLY and stored exactly as the hunter saw it — including any exchange suffix, e.g. \\"AAPL\\", \\"VOW3.DE\\", \\"1299.HK\\", \\"9984.T\\". Do NOT strip the suffix: \\"VOW3\\" matches nothing. Dracul stores every research cell under its ticker as the topic, so this is how you read one symbol's own history. There is no \\"symbol\\" field — omitting topic returns the newest cells of unrelated symbols."},
                      "tags":{"type":"array","items":{"type":"string"},"description":"Matches cells carrying ANY of these tags. Tags actually present on Dracul cells are the discovering agent and the cell kind — e.g. \\"daywalker_alert\\", \\"strigoi-echo\\", \\"prey\\", \\"exit_signal\\", \\"gropar\\". Prefer \\"topic\\" for a per-symbol lookup: tags are the coarser filter."},
                      "signal":{"type":"string","description":"Cell signal class. Dracul research cells are always \\"events\\"."},
                      "status":{"type":"string","enum":["committed","pending","rejected"],"description":"Moderation status of the cell; defaults to \\"committed\\"."}
                    },
                    "required":["realm"],
                    "additionalProperties":false
                  },
                  "limit":{"type":"integer","description":"Maximum number of cells to return."}
                },"required":["where"]}
                """);
        this.entry = new ToolCatalogEntry(NAME,
                "Searches prior research memory (HiveMem, realm-confined). Always pass "
                        + "where.realm=\"dracul-research\" AND where.topic=\"<TICKER>\" — the ticker "
                        + "is stored as the cell topic. There is no \"symbol\" field, and an "
                        + "unsupported where key fails the call. Without where.topic you get the "
                        + "newest cells of unrelated symbols, not this symbol's history.",
                input, null, 8, false, null);
    }

    @Override
    public List<ToolCatalogEntry> catalogEntries() {
        return List.of(entry);
    }
}
