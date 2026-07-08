package de.visterion.dracul.executor;

import de.visterion.dracul.agent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
class ExecutorDefaults {

    static final String NAME = "executor";

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
                                new ToolBinding("submit_decision", null, null, 4)));
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
                    "qty": {"type": "number"},
                    "limit_price": {"type": ["number", "null"]},
                    "stop_price": {"type": "number"},
                    "take_profit": {"type": ["number", "null"]}
                  },
                  "required": ["signal_id", "symbol", "side", "qty", "stop_price"]
                }
                """);

        return List.of(
                new ToolCatalogEntry("fetch_pending_signals",
                        "Return executor signals awaiting a decision.",
                        empty, "/api/executor/tools/fetch-pending-signals", 30),
                new ToolCatalogEntry("get_account",
                        "Return paper-broker account snapshot.",
                        connectionInput, "/api/executor/tools/get-account", 30),
                new ToolCatalogEntry("list_positions",
                        "Return current broker positions.",
                        connectionInput, "/api/executor/tools/list-positions", 30),
                new ToolCatalogEntry("place_entry",
                        "Place a guarded bracket entry (server enforces vetos + order guard).",
                        placeEntryInput, "/api/executor/tools/place-entry", 60),
                new ToolCatalogEntry("submit_decision",
                        "Record the executor's ENTER/SKIP decisions for processed signals.",
                        empty, "/api/executor/tools/submit-decision", 30));
    }
}
