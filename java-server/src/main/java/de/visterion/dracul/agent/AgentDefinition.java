package de.visterion.dracul.agent;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** Declarative, runtime-configurable spec for one agent. Paths are relative; the
 *  registrar prepends dracul.public-url at registration time. */
public record AgentDefinition(
        String name,
        String modelPurpose,
        String promptText,        // base prompt, BEFORE language directive
        JsonNode outputSchema,
        String schedule,          // cron; null allowed
        int maxTurns,
        int maxRunSeconds,
        String completionPath,
        String eventSourcePath,           // null unless streaming
        Integer sessionDurationSeconds,   // null unless streaming
        Integer pollIntervalSeconds,      // null unless streaming
        boolean enabled,
        List<ToolBinding> tools
) {
    public boolean isStreaming() {
        return eventSourcePath != null;
    }
}
