package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.echo.enabled", havingValue = "true")
class EchoDefaults {

    private static final String NAME = "strigoi-echo";
    private static final String FETCH = "fetch_recent_pead_candidates";

    @Bean
    AgentDefaultProvider echoDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.strigoi.echo.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-pead.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{\"lookback_days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":30}}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns positive-surprise PEAD candidates detected in the last N days.",
                input, "/api/strigoi-echo/tools/fetch-candidates", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "routine",
                        PromptDocument.bodyFromClasspath("prompts/strigoi-echo.md"), schema,
                        schedule, 25, 1800,
                        "/api/strigoi-echo/complete",
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
