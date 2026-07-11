package de.visterion.dracul.executor;

import de.visterion.dracul.agent.AgentDefinitionStore;
import de.visterion.dracul.agent.PromptHashes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Signal attributability: the emitting agent's "version" is a short hash of its
 * stored prompt text (agent_definition has no semantic version column). Spec veto #1
 * requires a non-blank agent_version on every entry signal.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class AgentVersionResolver {

    private final AgentDefinitionStore store;

    public AgentVersionResolver(AgentDefinitionStore store) {
        this.store = store;
    }

    public String versionFor(String agentName) {
        return store.find(agentName)
                .map(def -> PromptHashes.hash(def.promptText()))
                .orElse("unknown");
    }
}
