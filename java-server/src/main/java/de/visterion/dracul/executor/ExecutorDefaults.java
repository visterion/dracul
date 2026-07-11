package de.visterion.dracul.executor;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
class ExecutorDefaults {

    static final String NAME = "executor";

    /** Named to avoid ambiguity with any other Clock bean; inject via
     *  {@code @Qualifier("executorClock")}. */
    @Bean
    Clock executorClock() {
        return Clock.systemUTC();
    }

    @Bean
    AgentDefaultProvider executorAgentDefaults(
            ObjectMapper mapper,
            @Value("${dracul.executor.schedule:}") String schedule) {
        var schema = AgentResources.readSchema(mapper, "schemas/executor-decision.json");
        String resolvedSchedule = (schedule == null || schedule.isBlank()) ? null : schedule;
        List<ToolCatalogEntry> entries = catalogEntries(mapper);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        AgentResources.classpath("prompts/executor.md"), schema,
                        resolvedSchedule, 25, 1800,
                        "/api/executor/complete",
                        null, null, null, true,
                        List.of(
                                new ToolBinding("fetch_pending_signals", null, null, 0),
                                new ToolBinding("get_account", null, null, 1),
                                new ToolBinding("list_positions", null, null, 2),
                                new ToolBinding("place_entry", null, null, 3),
                                new ToolBinding("submit_decision", null, null, 4),
                                new ToolBinding("fetch_open_positions", null, null, 5),
                                new ToolBinding("exit_position", null, null, 6),
                                new ToolBinding("add_tranche", null, null, 7)));
            }

            @Override
            public List<ToolCatalogEntry> catalogEntries() {
                return entries;
            }
        };
    }

    private static List<ToolCatalogEntry> catalogEntries(ObjectMapper mapper) {
        var empty = AgentResources.parseJson(mapper, "{\"type\":\"object\",\"properties\":{}}");
        var connectionInput = AgentResources.parseJson(mapper, """
                {"type":"object","properties":{"connection":{"type":"string"}}}
                """);
        var placeEntryInput = AgentResources.parseJson(mapper, """
                {
                  "type": "object",
                  "properties": {
                    "signal_id": {"type": "string"},
                    "symbol": {"type": "string"},
                    "side": {"type": "string", "enum": ["BUY", "SELL"]},
                    "limit_price": {"type": ["number", "null"]},
                    "stop_price": {"type": "number"},
                    "take_profit": {"type": ["number", "null"]},
                    "confidence": {"type": ["number", "null"], "minimum": 0, "maximum": 1}
                  },
                  "required": ["signal_id", "symbol", "side", "stop_price"]
                }
                """);
        var exitPositionInput = AgentResources.parseJson(mapper, """
                {
                  "type": "object",
                  "properties": {
                    "symbol": {"type": "string"},
                    "reason": {"type": "string"},
                    "confidence": {"type": ["number", "null"]},
                    "reasoning": {"type": "string"},
                    "fraction": {"type": ["number", "null"], "enum": [0.33, 0.5, 1.0, null]}
                  },
                  "required": ["symbol"]
                }
                """);
        var addTrancheInput = AgentResources.parseJson(mapper, """
                {
                  "type": "object",
                  "properties": {
                    "symbol": {"type": "string"},
                    "reason": {"type": "string"}
                  },
                  "required": ["symbol", "reason"]
                }
                """);

        return List.of(
                new ToolCatalogEntry("fetch_pending_signals",
                        "Return executor signals awaiting a decision.",
                        empty, "/api/executor/tools/fetch-pending-signals", 30),
                new ToolCatalogEntry("get_account",
                        "Return broker account snapshot.",
                        connectionInput, "/api/executor/tools/get-account", 30),
                new ToolCatalogEntry("list_positions",
                        "Return current broker positions.",
                        connectionInput, "/api/executor/tools/list-positions", 30),
                new ToolCatalogEntry("place_entry",
                        "Place a guarded bracket entry (server enforces vetos + order guard). "
                                + "qty is computed server-side (tranche sizing).",
                        placeEntryInput, "/api/executor/tools/place-entry", 60),
                new ToolCatalogEntry("submit_decision",
                        "Record the executor's ENTER/SKIP decisions for processed signals.",
                        empty, "/api/executor/tools/submit-decision", 30),
                new ToolCatalogEntry("fetch_open_positions",
                        "Return open positions enriched with price/ATR/chandelier/R/MFE and soft-trigger state (runs reconciliation, hard exits, and stop-ratchet server-side first).",
                        empty, "/api/executor/tools/fetch-open-positions", 30),
                new ToolCatalogEntry("exit_position",
                        "Close or scale out of an open position (soft-trigger exit). Always permitted. "
                                + "fraction defaults to 1.0 (full close); 0.33/0.5 partial exits are "
                                + "code-gated by a trim ladder keyed on the position's trim_count -- "
                                + "the LLM may exit more aggressively than the ladder floor, never less.",
                        exitPositionInput, "/api/executor/tools/exit-position", 60),
                new ToolCatalogEntry("add_tranche",
                        "Add a code-verified tranche 2 to an open tranche-1 position "
                                + "(server re-checks eligibility, sizing, heat and budget; qty is computed server-side).",
                        addTrancheInput, "/api/executor/tools/add-tranche", 60));
    }
}
