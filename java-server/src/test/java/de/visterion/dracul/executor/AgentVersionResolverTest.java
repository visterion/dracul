package de.visterion.dracul.executor;

import de.visterion.dracul.agent.AgentDefinition;
import de.visterion.dracul.agent.AgentDefinitionStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentVersionResolverTest {

    private AgentDefinition defWithPrompt(String promptText) {
        return new AgentDefinition(
                "strigoi-spin", "spinoff hunter", promptText, null,
                null, 5, 300, "/completion", null, null, null, true, java.util.List.of());
    }

    @Test
    void hashesPromptTextStable() {
        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        when(store.find("strigoi-spin")).thenReturn(Optional.of(defWithPrompt("You are strigoi-spin...")));

        AgentVersionResolver r = new AgentVersionResolver(store);
        String v = r.versionFor("strigoi-spin");

        assertThat(v).startsWith("p-").hasSize(14);
        assertThat(r.versionFor("strigoi-spin")).isEqualTo(v); // deterministic
    }

    @Test
    void unknownAgentYieldsUnknown() {
        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        when(store.find("ghost")).thenReturn(Optional.empty());

        assertThat(new AgentVersionResolver(store).versionFor("ghost")).isEqualTo("unknown");
    }
}
