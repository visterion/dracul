package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.lazarus.enabled", havingValue = "true")
class LazarusDefaults {

    private static final String NAME = "strigoi-lazarus";
    private static final String FETCH = "fetch_quality_at_low_candidates";

    @Bean
    AgentDefaultProvider lazarusDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.strigoi.lazarus.schedule}") String schedule,
            @Value("${dracul.strigoi.lazarus.fetch-timeout-seconds:600}") int fetchTimeoutSeconds) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-lazarus.json");
        JsonNode input = AgentResources.parseJson(mapper, "{\"type\":\"object\",\"properties\":{}}");
        // D7: the universe is market-wide (S&P 500 via Agora) plus the watchlist, so one fetch
        // walks ~500 symbols through a cheap pre-filter before spending Finnhub-routed
        // fundamentals calls on the survivors. The old 30 s webhook budget was sized for a
        // handful of watchlist rows and cannot cover that.
        var entry = new ToolCatalogEntry(FETCH,
                "Returns names from the screened market universe (S&P 500 plus the watchlist) "
                        + "currently trading near their 52-week low, with fundamentals.",
                input, "/api/strigoi-lazarus/tools/fetch-candidates", fetchTimeoutSeconds);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/strigoi-lazarus.md"), schema,
                        schedule, 25, 1800,
                        "/api/strigoi-lazarus/complete",
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
