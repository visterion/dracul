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

    /**
     * Webhook timeout for the fetch tool, in seconds. Must enclose the WHOLE Agora budget the
     * same request spends inside itself.
     *
     * <p>Since BUG-S1b that is no longer one call: {@code AgoraFilings.recentForm4} issues one
     * {@code get_form4_transactions} call PER DAY of the lookback, bounded by
     * {@code AgoraFilings.MAX_WINDOW_SLICES}. The arithmetic:
     * <pre>
     *   10 slices x 45 000 ms  (dracul.agora.tool-timeout-ms[get_form4_transactions])
     *   = 450 s worst case     (~334 s at the 33.4 s per call measured on prod 2026-08-04)
     *   600 s declared here    -> 150 s / 33% headroom, and a third of the run budget below
     * </pre>
     * Vistierie's {@code max_run_seconds} for strigoi-insider (1800 s, see the definition below)
     * encloses that with room for the reasoning turns. Pinned by
     * {@code InsiderToolTimeoutBudgetTest}, which asserts the RELATIONSHIP (slice cap x the
     * CONFIGURED Agora budget < this value) rather than a remembered number, so the three cannot
     * drift apart. Changing this value needs an agent-definition reset.
     */
    static final int FETCH_TIMEOUT_SECONDS = 600;

    @Bean
    AgentDefaultProvider insiderDefaultProvider(
            ObjectMapper mapper,
            @Value("${dracul.strigoi.insider.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list.json");
        JsonNode input = AgentResources.parseJson(mapper,
                "{\"type\":\"object\",\"properties\":{\"lookback_days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":30}}}");
        var entry = new ToolCatalogEntry(FETCH,
                "Returns insider buying clusters detected in the last N days.",
                input, "/api/strigoi-insider/tools/fetch-clusters", FETCH_TIMEOUT_SECONDS);
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
