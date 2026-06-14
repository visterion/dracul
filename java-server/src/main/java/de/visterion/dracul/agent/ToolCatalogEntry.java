package de.visterion.dracul.agent;

import tools.jackson.databind.JsonNode;

/** Code-bound description of an available tool. Routing + input schema live here. */
public record ToolCatalogEntry(
        String toolName,
        String defaultDescription,
        JsonNode inputSchema,
        String callbackPath,
        int timeoutSeconds,
        boolean cacheable,
        Integer cacheTtlSeconds
) {
    /** Convenience: cacheable at the global default TTL. Keeps existing call sites unchanged. */
    public ToolCatalogEntry(String toolName, String defaultDescription,
                            JsonNode inputSchema, String callbackPath, int timeoutSeconds) {
        this(toolName, defaultDescription, inputSchema, callbackPath, timeoutSeconds, true, null);
    }
}
