package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.gropar.enabled", havingValue = "true")
class GroparDefaults {

    private static final String NAME = "gropar";
    private static final String FETCH = "fetch_held_positions";

    @Bean
    AgentDefaultProvider groparDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.gropar.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/exit-signal-list.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns all currently held watchlist positions enriched with deterministic exit indicators, fired rules, and the original thesis.",
                input, "/api/gropar/tools/fetch-held-positions", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/gropar.md"), schema,
                        schedule, 25, 1800,
                        "/api/gropar/complete",
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
