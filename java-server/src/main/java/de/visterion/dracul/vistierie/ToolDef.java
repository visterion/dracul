package de.visterion.dracul.vistierie;

import tools.jackson.databind.JsonNode;

public record ToolDef(
        String name,
        String description,
        JsonNode input_schema,
        String type,
        String target_agent,
        String webhook_url,
        Integer webhook_timeout_seconds,
        String mcp_server_url,
        String mcp_tool_name,
        Integer mcp_timeout_seconds
) {
    /** Back-compat constructor for existing http tools — keeps every call site unchanged. */
    public ToolDef(String name, String description, JsonNode input_schema, String type,
            String target_agent, String webhook_url, Integer webhook_timeout_seconds) {
        this(name, description, input_schema, type, target_agent, webhook_url,
                webhook_timeout_seconds, null, null, null);
    }
}
