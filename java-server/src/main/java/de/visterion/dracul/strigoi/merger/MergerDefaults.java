package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.merger.enabled", havingValue = "true")
class MergerDefaults {

    private static final String NAME = "strigoi-merger";
    private static final String FETCH = "fetch_recent_merger_candidates";

    @Bean
    AgentDefaultProvider mergerDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.strigoi.merger.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-merger.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{\"lookback_days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":120}}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns recent SEC merger deal filings (DEFM14A merger proxies, SC TO-T tender offers) from the last N days.",
                input, "/api/strigoi-merger/tools/fetch-candidates", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        AgentResources.classpath("prompts/strigoi-merger.md"), schema,
                        schedule, 25, 1800,
                        "/api/strigoi-merger/complete",
                        null, null, null, true,
                        List.of(new ToolBinding(FETCH, null, null, 0)));
            }

            @Override
            public List<ToolCatalogEntry> catalogEntries() {
                return List.of(entry);
            }
        };
    }
}
