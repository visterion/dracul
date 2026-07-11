package de.visterion.dracul.agent;

import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.vistierie.BudgetStatus;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class AgentBudgetGuardTest {

    private AgentDefinition scheduled(String name) {
        return new AgentDefinition(name, "routine", "PROMPT",
                null, "0 0 7 * * *", 25, 1800, "/api/" + name + "/complete",
                null, null, null, true, List.of());
    }

    private AgentDefinition triggerOnly(String name) {
        return new AgentDefinition(name, "routine", "PROMPT",
                null, null, 25, 1800, "/api/" + name + "/complete",
                null, null, null, true, List.of());
    }

    @Test
    void allNullCapsFlagsAgentAsMissing() {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(scheduled("strigoi-gropar")));
        var client = mock(VistierieClient.class);
        when(client.getAgentBudget("strigoi-gropar")).thenReturn(BudgetStatus.empty());
        var settings = mock(AppSettingsRepository.class);

        new AgentBudgetGuard(client, store, settings).checkAll();

        verify(settings).put("health.agent_budgets", "MISSING:strigoi-gropar");
    }

    @Test
    void realCapsResultInOkFlag() {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(scheduled("voievod")));
        var client = mock(VistierieClient.class);
        when(client.getAgentBudget("voievod")).thenReturn(
                new BudgetStatus(1_000_000L, 10_000_000L, null, null, 0, 0, false, false, false, false));
        var settings = mock(AppSettingsRepository.class);

        new AgentBudgetGuard(client, store, settings).checkAll();

        verify(settings).put("health.agent_budgets", "OK");
    }

    @Test
    void clientExceptionTreatedAsMissingAndDoesNotCrash() {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(scheduled("strigoi-echo")));
        var client = mock(VistierieClient.class);
        when(client.getAgentBudget("strigoi-echo")).thenThrow(new RuntimeException("boom"));
        var settings = mock(AppSettingsRepository.class);

        new AgentBudgetGuard(client, store, settings).checkAll();

        verify(settings).put("health.agent_budgets", "MISSING:strigoi-echo");
    }

    @Test
    void nullScheduleAgentsAreSkipped() {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(triggerOnly("daywalker")));
        var client = mock(VistierieClient.class);
        var settings = mock(AppSettingsRepository.class);

        new AgentBudgetGuard(client, store, settings).checkAll();

        verify(client, never()).getAgentBudget(anyString());
        verify(settings).put("health.agent_budgets", "OK");
    }

    @Test
    void multipleMissingAgentsAreJoined() {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(scheduled("strigoi-a"), scheduled("strigoi-b")));
        var client = mock(VistierieClient.class);
        when(client.getAgentBudget(anyString())).thenReturn(BudgetStatus.empty());
        var settings = mock(AppSettingsRepository.class);

        new AgentBudgetGuard(client, store, settings).checkAll();

        verify(settings).put("health.agent_budgets", "MISSING:strigoi-a,strigoi-b");
    }
}
