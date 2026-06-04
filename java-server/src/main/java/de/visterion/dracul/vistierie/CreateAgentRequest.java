package de.visterion.dracul.vistierie;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record CreateAgentRequest(
        String name,
        String system_prompt,
        String model_purpose,
        List<ToolDef> tools,
        JsonNode output_schema,
        Integer max_turns,
        Integer max_run_seconds,
        String webhook_token,
        String schedule,
        String completion_webhook,
        String completion_webhook_token,
        String event_source_url,
        Integer session_duration_seconds,
        Integer poll_interval_seconds
) {
    /** Back-compat constructor for non-streaming agents (Echo, Insider). */
    public CreateAgentRequest(
            String name, String system_prompt, String model_purpose, List<ToolDef> tools,
            JsonNode output_schema, Integer max_turns, Integer max_run_seconds,
            String webhook_token, String schedule, String completion_webhook,
            String completion_webhook_token) {
        this(name, system_prompt, model_purpose, tools, output_schema, max_turns,
                max_run_seconds, webhook_token, schedule, completion_webhook,
                completion_webhook_token, null, null, null);
    }
}
