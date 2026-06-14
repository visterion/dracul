package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentDefinitionControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private AgentDefinition echoDef() {
        return new AgentDefinition("strigoi-echo", "routine", "orig prompt",
                json.createObjectNode().put("type", "object"), "0 0 7 * * *", 25, 1800,
                "/api/strigoi-echo/complete", null, null, null, true,
                List.of(new ToolBinding("fetch_recent_pead_candidates", null, null, 0)));
    }

    private AgentToolCatalog catalog() {
        var entry = new ToolCatalogEntry("fetch_recent_pead_candidates", "d",
                json.createObjectNode(), "/api/strigoi-echo/tools/fetch-candidates", 30);
        AgentDefaultProvider stub = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return echoDef(); }
            @Override public List<ToolCatalogEntry> catalogEntries() { return List.of(entry); }
        };
        return new AgentToolCatalog(List.of(stub));
    }

    private AgentDefaultProvider echoProvider() {
        return new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return echoDef(); }
            @Override public List<ToolCatalogEntry> catalogEntries() { return List.of(); }
        };
    }

    private AgentDefinitionController controller(AgentDefinitionStore store,
                                                ApplicationEventPublisher events) {
        return new AgentDefinitionController(store, new AgentDefinitionValidator(catalog()),
                List.of(echoProvider()), events);
    }

    @Test
    void putValidEditPersistsAndPublishesEvent() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("strigoi-echo")).thenReturn(Optional.of(echoDef()));
        var events = mock(ApplicationEventPublisher.class);

        var body = new AgentDefinitionController.EditRequest(
                "new prompt body", "0 0 8 * * *", "routine", true, 25, 1800,
                List.of(new AgentDefinitionController.ToolEdit("fetch_recent_pead_candidates", null)));

        var resp = controller(store, events).put("strigoi-echo", body);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(store).save(argThat(d -> d.promptText().equals("new prompt body")));
        verify(events).publishEvent(new AgentDefinitionChangedEvent("strigoi-echo"));
    }

    @Test
    void putEmptyPromptIsRejected() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("strigoi-echo")).thenReturn(Optional.of(echoDef()));
        var body = new AgentDefinitionController.EditRequest(
                "  ", "0 0 8 * * *", "routine", true, 25, 1800, List.of());
        var resp = controller(store, mock(ApplicationEventPublisher.class)).put("strigoi-echo", body);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void putUnknownToolIsRejected() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("strigoi-echo")).thenReturn(Optional.of(echoDef()));
        var body = new AgentDefinitionController.EditRequest(
                "ok", "0 0 8 * * *", "routine", true, 25, 1800,
                List.of(new AgentDefinitionController.ToolEdit("not_a_tool", null)));
        var resp = controller(store, mock(ApplicationEventPublisher.class)).put("strigoi-echo", body);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void putUnknownAgentIs404() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("ghost")).thenReturn(Optional.empty());
        var body = new AgentDefinitionController.EditRequest(
                "ok", "0 0 8 * * *", "routine", true, 25, 1800, List.of());
        var resp = controller(store, mock(ApplicationEventPublisher.class)).put("ghost", body);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void resetRestoresDefaultAndPublishes() {
        var store = mock(AgentDefinitionStore.class);
        var events = mock(ApplicationEventPublisher.class);
        var resp = controller(store, events).reset("strigoi-echo");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(store).save(argThat(d -> d.name().equals("strigoi-echo") && d.promptText().equals("orig prompt")));
        verify(events).publishEvent(new AgentDefinitionChangedEvent("strigoi-echo"));
    }
}
