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
     * Webhook timeout for the fetch tool, in seconds. Must stay strictly larger than the Agora
     * budget the same request spends inside itself
     * (dracul.agora.tool-timeout-ms[get_form4_transactions], 45 s) — a market-wide Form-4 scan
     * measured 33.4 s on prod 2026-08-04 and its worst case is ~39 s. Pinned by
     * {@code InsiderToolTimeoutBudgetTest}. Changing it needs an agent-definition reset.
     *
     * <p><b>OPEN, 2026-08-06 (BUG-S1b):</b> {@code AgoraFilings.recentForm4} now issues ONE
     * {@code get_form4_transactions} call PER DAY of the lookback. The per-CALL budget is
     * unchanged, but a 7-day fetch spends up to 7 x 45 s = 315 s (~234 s at the measured 33.4 s)
     * inside itself, so 60 s no longer encloses it — this value must be raised to at least 360 s
     * before the fetch tool's timeout is relied on. It is deliberately NOT raised here: it is
     * baked into the agent definition and changing it requires the operational
     * agent-definition-reset procedure, not a code change. Vistierie's {@code max_run_seconds}
     * (1800 s, below) still encloses the new total with room to spare.
     */
    static final int FETCH_TIMEOUT_SECONDS = 60;

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
