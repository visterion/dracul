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
        JsonNode input = AgentResources.parseJson(mapper, """
                {"type":"object","properties":{"where":{"type":"object"},"limit":{"type":"integer"}},"required":["where"]}
                """);
        this.entry = new ToolCatalogEntry(NAME,
                "Searches prior research memory (HiveMem, realm-confined). Always pass "
                        + "where.realm=\"dracul-research\".",
                input, null, 8, false, null);
    }

    @Override
    public List<ToolCatalogEntry> catalogEntries() {
        return List.of(entry);
    }
}
