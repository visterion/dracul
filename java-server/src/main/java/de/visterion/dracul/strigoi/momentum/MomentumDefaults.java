package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.agent.AgentDefaultProvider;
import de.visterion.dracul.agent.AgentDefinition;
import de.visterion.dracul.agent.AgentResources;
import de.visterion.dracul.agent.PromptDocument;
import de.visterion.dracul.agent.ToolBinding;
import de.visterion.dracul.agent.ToolCatalogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.momentum.enabled", havingValue = "true")
class MomentumDefaults {

    static final String NAME = MomentumSettings.AGENT;
    static final String FETCH = "fetch_momentum_ranking";

    /** Bound once; a malformed value fails startup. position-pct and min-entry-qty are the same
     *  keys the executor's ConvictionProfileConfig reads for exit profile MOMENTUM. */
    @Bean
    MomentumSettings momentumSettings(
            @Value("${dracul.strigoi.momentum.top-n:10}") int topN,
            @Value("${dracul.strigoi.momentum.refill-n:10}") int refillN,
            @Value("${dracul.strigoi.momentum.catch-up-weekdays:3}") int catchUpWeekdays,
            @Value("${dracul.strigoi.momentum.universe-min:480}") int universeMin,
            @Value("${dracul.strigoi.momentum.completeness-floor:0.95}") BigDecimal completenessFloor,
            @Value("${dracul.strigoi.momentum.position-pct:0.025}") BigDecimal positionPct,
            @Value("${dracul.strigoi.momentum.lookback-days:252}") int lookbackDays,
            @Value("${dracul.strigoi.momentum.skip-days:21}") int skipDays,
            @Value("${dracul.strigoi.momentum.gap-suspect-pct:0.35}") BigDecimal gapSuspectPct,
            @Value("${dracul.strigoi.momentum.min-price:5}") BigDecimal minPrice,
            @Value("${dracul.strigoi.momentum.min-entry-qty:1}") int minEntryQty,
            @Value("${dracul.strigoi.momentum.exclude-symbols:GOOG,FOX,NWS}") String excludeSymbols,
            @Value("${dracul.executor.connection:depot-1}") String executorConnection) {
        return new MomentumSettings(topN, refillN, catchUpWeekdays, universeMin, completenessFloor,
                positionPct, lookbackDays, skipDays, gapSuspectPct, minPrice, minEntryQty,
                MomentumSettings.parseSymbols(excludeSymbols), executorConnection);
    }

    /** One tool, uncached (the answer is THIS run's snapshot), 600 s (≈5 batch calls of 100
     *  symbols + 20 profile reads). No `search`: the LLM only vetoes the code's ranking. */
    @Bean
    AgentDefaultProvider momentumDefaultProvider(ObjectMapper mapper,
            @Value("${dracul.strigoi.momentum.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-momentum.json");
        JsonNode empty = AgentResources.parseJson(mapper, "{\"type\":\"object\",\"properties\":{}}");
        var fetch = new ToolCatalogEntry(FETCH,
                "Returns tonight's 12-1 momentum ranking decided by code: whether a monthly rebalance "
                        + "is due, the Top 10 and the next 10 refill candidates of the S&P 500 with "
                        + "facts, the held momentum positions, data-suspect names and the data health. "
                        + "Call it exactly once.",
                empty, "/api/strigoi-momentum/tools/fetch-ranking", 600, false, null);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/strigoi-momentum.md"), schema,
                        schedule, 6, 1800,
                        "/api/strigoi-momentum/complete",
                        null, null, null, true,
                        List.of(new ToolBinding(FETCH, null, null, 0)));
            }

            @Override
            public List<ToolCatalogEntry> catalogEntries() {
                return List.of(fetch);
            }
        };
    }
}
