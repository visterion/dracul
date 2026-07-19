package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.spin.enabled", havingValue = "true")
class SpinDefaults {

    private static final String NAME = "strigoi-spin";
    private static final String FETCH = "fetch_recent_spinoff_candidates";

    @Bean
    AgentDefaultProvider spinDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.strigoi.spin.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-spin.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{\"lookback_days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":90}}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns recent SEC Form 10-12B spin-off registrations from the last N days.",
                input, "/api/strigoi-spin/tools/fetch-candidates", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/strigoi-spin.md"), schema,
                        schedule, 25, 1800,
                        "/api/strigoi-spin/complete",
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
