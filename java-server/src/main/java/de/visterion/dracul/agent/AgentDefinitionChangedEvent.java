package de.visterion.dracul.agent;

/** Published after an agent definition is edited via REST; triggers re-registration. */
public record AgentDefinitionChangedEvent(String name) {}
