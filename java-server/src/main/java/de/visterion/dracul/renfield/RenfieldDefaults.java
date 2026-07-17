package de.visterion.dracul.renfield;

import de.visterion.dracul.agent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Reasoning-tier, trigger-only daily watchlist reviewer (D4/D6). Never scheduled in
 * Vistierie ({@code schedule = null}) — a Vistierie-cron-fired run would carry no
 * Dracul-assembled payload; {@code RenfieldScheduler} triggers it with the input.
 * Output is ranked trade proposals only — strictly no auto-trade.
 */
@Configuration
@ConditionalOnProperty(value = "dracul.renfield.enabled", havingValue = "true")
class RenfieldDefaults {

    static final String NAME = "renfield";

    @Bean
    AgentDefaultProvider renfieldDefaultProvider(ObjectMapper mapper) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/renfield-review.json");
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/renfield.md"), schema,
                        null, 4, 600,
                        "/api/renfield/complete",
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
