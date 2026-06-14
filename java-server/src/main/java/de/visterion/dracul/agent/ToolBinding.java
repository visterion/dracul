package de.visterion.dracul.agent;

import tools.jackson.databind.JsonNode;

/** Assignment of a catalog tool to an agent, with optional content overrides. */
public record ToolBinding(
        String toolName,
        String description,   // null → use catalog default
        JsonNode defaultParams,
        int ordinal
) {}
