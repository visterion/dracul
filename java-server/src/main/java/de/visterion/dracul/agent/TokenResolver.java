package de.visterion.dracul.agent;

/** Resolves an agent's webhook token from configuration. */
@FunctionalInterface
public interface TokenResolver {
    String resolve(String agentName);
}
