package de.visterion.dracul.vistierie;

import tools.jackson.databind.JsonNode;

public record ToolDef(
        String name,
        String description,
        JsonNode input_schema,
        String type,
        String target_agent,
        String webhook_url,
        Integer webhook_timeout_seconds
) {}
