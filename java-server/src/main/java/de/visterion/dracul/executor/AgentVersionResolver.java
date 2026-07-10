package de.visterion.dracul.executor;

import de.visterion.dracul.agent.AgentDefinitionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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
                .map(def -> "p-" + sha256Hex(def.promptText()).substring(0, 12))
                .orElse("unknown");
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
