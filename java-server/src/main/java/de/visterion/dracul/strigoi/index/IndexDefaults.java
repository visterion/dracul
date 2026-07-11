package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.index.enabled", havingValue = "true")
class IndexDefaults {

    private static final String NAME = "strigoi-index";
    private static final String FETCH = "fetch_recent_index_additions";

    @Bean
    AgentDefaultProvider indexDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.strigoi.index.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-index.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{\"lookback_days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":90}}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns S&P 500 constituents added to the index within the last N days.",
                input, "/api/strigoi-index/tools/fetch-candidates", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "routine",
                        PromptDocument.bodyFromClasspath("prompts/strigoi-index.md"), schema,
                        schedule, 25, 1800,
                        "/api/strigoi-index/complete",
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
