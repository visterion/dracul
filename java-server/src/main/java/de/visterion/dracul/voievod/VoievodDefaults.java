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
@ConditionalOnProperty(value = "dracul.voievod.enabled", havingValue = "true")
class VoievodDefaults {

    private static final String NAME = "voievod";
    private static final String FETCH = "fetch_consensus_clusters";

    @Bean
    AgentDefaultProvider voievodDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.voievod.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/verdict-list.json");
        JsonNode input = AgentResources.parseJson(mapper, "{\"type\":\"object\",\"properties\":{}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns symbols flagged by two or more different Strigoi, with the contributing prey.",
                input, "/api/voievod/tools/fetch-candidates", 30);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/voievod.md"), schema,
                        schedule, 25, 1800,
                        "/api/voievod/complete",
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
