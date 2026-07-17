package de.visterion.dracul.daywalker;

import de.visterion.dracul.agent.AgentDefinition;
import de.visterion.dracul.agent.AgentDefinitionController;
import de.visterion.dracul.agent.AgentDefinitionStore;
import de.visterion.dracul.agent.AgentDefinitionValidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the T1.3 rollout mechanism (spec §4.3 R1-C1 / §6): AgentDefinitionBootstrap only
 * seeds insertIfAbsent, so a deploy alone never updates a registered daywalker definition.
 * POST /api/settings/agents/daywalker/definition/reset must return (and save) the
 * provider default whose outputSchema now contains event_type.
 */
class DaywalkerDefinitionResetTest {

    @Test
    void resetReturnsProviderDefaultWhoseSchemaContainsEventType() {
        var provider = new DaywalkerDefaults().daywalkerDefaultProvider(
                new ObjectMapper(), "0 30 13 * * 1-5", 23400, 300);
        var store = mock(AgentDefinitionStore.class);
        when(store.find("daywalker")).thenReturn(Optional.empty());
        var controller = new AgentDefinitionController(store,
                mock(AgentDefinitionValidator.class), List.of(provider),
                mock(ApplicationEventPublisher.class));

        var response = controller.reset("daywalker");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        AgentDefinition def = (AgentDefinition) response.getBody();
        assertThat(def).isNotNull();
        assertThat(def.name()).isEqualTo("daywalker");
        assertThat(def.outputSchema().path("properties").has("event_type"))
                .as("reset default must carry the extended schema").isTrue();
        verify(store).save(def);
    }
}
