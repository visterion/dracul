package de.visterion.dracul.voievod;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.voievod-outcome.enabled", havingValue = "true")
class VoievodOutcomeDefaults {

    private static final String NAME = "voievod-outcome";
    private static final String FETCH = "fetch_elapsed_prey";

    @Bean
    AgentDefaultProvider voievodOutcomeDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.voievod-outcome.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/voievod-outcome.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{\"lookback_days\":{\"type\":\"integer\"}}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns prey whose horizon elapsed more than 30 days ago and that have not "
                        + "yet been reviewed, with condensed price history since discovery.",
                input, "/webhook/voievod-outcome/tools/fetch-elapsed-prey", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/voievod-outcome.md"), schema,
                        schedule, 25, 1800,
                        "/webhook/voievod-outcome/complete",
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
