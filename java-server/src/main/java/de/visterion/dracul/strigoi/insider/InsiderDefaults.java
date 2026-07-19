package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.insider.enabled", havingValue = "true")
class InsiderDefaults {

    private static final String NAME = "strigoi-insider";
    private static final String FETCH = "fetch_recent_clusters";

    @Bean
    AgentDefaultProvider insiderDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.strigoi.insider.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{\"lookback_days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":30}}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns insider buying clusters detected in the last N days.",
                input, "/api/strigoi-insider/tools/fetch-clusters", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/strigoi-insider.md"), schema,
                        schedule, 25, 1800,
                        "/api/strigoi-insider/complete",
                        null, null, null, true,
                        List.of(new ToolBinding(FETCH, null, null, 0),
                                new ToolBinding("search", null, null, 1)));
            }

            @Override
            public List<ToolCatalogEntry> catalogEntries() {
                return List.of(entry);
            }
        };
    }
}
