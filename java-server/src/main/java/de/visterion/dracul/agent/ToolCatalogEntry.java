package de.visterion.dracul.agent;

import tools.jackson.databind.JsonNode;

/** Code-bound description of an available tool. Routing + input schema live here. */
public record ToolCatalogEntry(
        String toolName,
        String defaultDescription,
        JsonNode inputSchema,
        String callbackPath,
        int timeoutSeconds
) {}
