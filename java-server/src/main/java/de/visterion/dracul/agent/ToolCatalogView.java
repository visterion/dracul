package de.visterion.dracul.agent;

/** Read-only view of an available tool, for the agent-config edit UI. */
public record ToolCatalogView(String toolName, String defaultDescription) {}
