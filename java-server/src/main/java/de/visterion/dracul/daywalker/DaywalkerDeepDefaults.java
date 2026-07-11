package de.visterion.dracul.daywalker;

import de.visterion.dracul.agent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Reasoning-tier, trigger-only escalation agent: gets a second opinion on a
 * low-confidence CRITICAL Daywalker assessment. Never scheduled — Vistierie runs it
 * only when {@link DaywalkerCompletionService} calls {@code triggerRun("daywalker-deep")}.
 */
@Configuration
@ConditionalOnProperty(value = "dracul.daywalker-deep.enabled", havingValue = "true")
class DaywalkerDeepDefaults {

    static final String NAME = "daywalker-deep";

    @Bean
    AgentDefaultProvider daywalkerDeepDefaultProvider(ObjectMapper mapper) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/daywalker-deep.json");
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/daywalker-deep.md"), schema,
                        null, 4, 300,
                        "/api/daywalker-deep/complete",
                        null, null, null, true,
                        List.of());
            }

            @Override
            public List<ToolCatalogEntry> catalogEntries() {
                return List.of();
            }
        };
    }
}
