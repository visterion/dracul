package de.visterion.dracul.daywalker;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.daywalker.enabled", havingValue = "true")
class DaywalkerDefaults {

    @Bean
    AgentDefaultProvider daywalkerDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.daywalker.session-cron}") String schedule,
            @Value("${dracul.daywalker.session-duration:23400}") int sessionDuration,
            @Value("${dracul.daywalker.poll-interval:300}") int pollInterval) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/daywalker-assessment.json");
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        "daywalker", "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/daywalker.md"), schema,
                        schedule, 8, 600,
                        "/api/daywalker/complete",
                        "/api/daywalker/events", sessionDuration, pollInterval,
                        true,
                        List.of());
            }

            @Override
            public List<ToolCatalogEntry> catalogEntries() {
                return List.of();
            }
        };
    }
}
