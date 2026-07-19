package de.visterion.dracul.agent;

import de.visterion.dracul.i18n.LanguageChangedEvent;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.vistierie.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GenericAgentRegistrarTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private AgentDefinition echo() {
        return new AgentDefinition("strigoi-echo", "routine", "BASE PROMPT",
                json.createObjectNode().put("type", "object"),
                "0 0 7 * * *", 25, 1800, "/api/strigoi-echo/complete",
                null, null, null, true,
                List.of(new ToolBinding("fetch_recent_pead_candidates", "desc", null, 0)));
    }

    private AgentDefinition streamingEcho() {
        return new AgentDefinition("strigoi-stream", "routine", "STREAM PROMPT",
                json.createObjectNode().put("type", "object"),
                null, 25, 1800, "/api/strigoi-stream/complete",
                "/api/strigoi-stream/events", 3600, 5, true,
                List.of());
    }

    private AgentToolCatalog catalog() {
        var entry = new ToolCatalogEntry("fetch_recent_pead_candidates", "catalog desc",
                json.createObjectNode(), "/api/strigoi-echo/tools/fetch-candidates", 30);
        AgentDefaultProvider stub = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return null; }
            @Override public List<ToolCatalogEntry> catalogEntries() { return List.of(entry); }
        };
        return new AgentToolCatalog(List.of(stub));
    }

    private GenericAgentRegistrar newRegistrar(VistierieClient client) {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(echo()));
        when(store.find("strigoi-echo")).thenReturn(Optional.of(echo()));
        var settings = mock(AppSettingsRepository.class);
        when(settings.getLanguage()).thenReturn("en");
        return new GenericAgentRegistrar(client, store, catalog(), settings,
                "https://dracul.example.com", name -> "tok-" + name,
                List.of(), json, "http://hivemem:8421", "read-tok");
    }

    private GenericAgentRegistrar newStreamingRegistrar(VistierieClient client) {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(streamingEcho()));
        when(store.find("strigoi-stream")).thenReturn(Optional.of(streamingEcho()));
        var settings = mock(AppSettingsRepository.class);
        when(settings.getLanguage()).thenReturn("en");
        return new GenericAgentRegistrar(client, store, catalog(), settings,
                "https://dracul.example.com", name -> "tok-" + name,
                List.of(), json, "http://hivemem:8421", "read-tok");
    }

    @Test
    void registersWhenAbsentWithPrefixedUrlsAndLanguageDirective() {
        var client = mock(VistierieClient.class);
        when(client.getAgent("strigoi-echo")).thenReturn(Optional.empty());

        newRegistrar(client).registerAll();

        var captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(client).registerAgent(captor.capture());
        var req = captor.getValue();
        assertThat(req.name()).isEqualTo("strigoi-echo");
        assertThat(req.system_prompt()).startsWith("BASE PROMPT");
        assertThat(req.completion_webhook())
                .isEqualTo("https://dracul.example.com/api/strigoi-echo/complete");
        assertThat(req.tools()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("fetch_recent_pead_candidates");
            assertThat(t.webhook_url())
                    .isEqualTo("https://dracul.example.com/api/strigoi-echo/tools/fetch-candidates");
            assertThat(t.description()).isEqualTo("desc");
        });
        assertThat(req.webhook_token()).isEqualTo("tok-strigoi-echo");
        assertThat(req.completion_webhook_token()).isEqualTo("tok-strigoi-echo");
    }

    @Test
    void updatesWhenExistingDiffers() {
        var client = mock(VistierieClient.class);
        // AgentDetail is a record (final) — construct a real instance with a differing system_prompt
        var existing = new AgentDetail(
                "id-1", "strigoi-echo",
                "OLD PROMPT",   // differs from "BASE PROMPT..." built by buildRequest
                "routine",
                List.of(), json.createObjectNode().put("type", "object"),
                25, 1800, false, 1,
                Instant.EPOCH, Instant.EPOCH,
                "0 0 7 * * *", null,
                "https://dracul.example.com/api/strigoi-echo/complete",
                "tok-strigoi-echo", null, null, null);
        when(client.getAgent("strigoi-echo")).thenReturn(Optional.of(existing));

        newRegistrar(client).registerAll();

        verify(client).updateAgent(eq("strigoi-echo"), any(UpdateAgentRequest.class));
        verify(client, never()).registerAgent(any());
    }

    @Test
    void languageChangedEventReRegistersAllEnabledAgents() {
        var client = mock(VistierieClient.class);
        when(client.getAgent("strigoi-echo")).thenReturn(Optional.empty());

        newRegistrar(client).onLanguageChanged(new LanguageChangedEvent("de"));

        verify(client).registerAgent(any(CreateAgentRequest.class));
    }

    @Test
    void agentDefinitionChangedEventReRegistersSingleAgent() {
        var client = mock(VistierieClient.class);
        when(client.getAgent("strigoi-echo")).thenReturn(Optional.empty());
        when(client.getAgent("ghost")).thenReturn(Optional.empty());

        var registrar = newRegistrar(client);

        // known agent → should register
        registrar.onChanged(new AgentDefinitionChangedEvent("strigoi-echo"));
        verify(client, times(1)).registerAgent(any(CreateAgentRequest.class));

        // unknown agent (store.find returns empty) → must NOT call registerAgent again
        registrar.onChanged(new AgentDefinitionChangedEvent("ghost"));
        verify(client, times(1)).registerAgent(any(CreateAgentRequest.class));
    }

    @Test
    void languageDirectiveIsAppendedToPrompt() {
        var client = mock(VistierieClient.class);
        when(client.getAgent("strigoi-echo")).thenReturn(Optional.empty());

        newRegistrar(client).registerAll();

        var captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(client).registerAgent(captor.capture());
        assertThat(captor.getValue().system_prompt())
                .contains("## Output language")
                .contains("English");
    }

    @Test
    void maxTurnsChangeTriggersUpdate() {
        var client = mock(VistierieClient.class);
        var registrar = newRegistrar(client);
        var def = echo();
        var desired = registrar.buildRequest(def);

        // AgentDetail matching desired in all fields EXCEPT max_turns (20 vs 25)
        var existing = new AgentDetail(
                "id-1", "strigoi-echo",
                desired.system_prompt(),  // same as desired (includes language directive)
                "routine",
                desired.tools(),  // same tools as desired
                desired.output_schema(),
                20, 1800, false, 1,  // max_turns=20 differs from 25
                Instant.EPOCH, Instant.EPOCH,
                "0 0 7 * * *", null,
                "https://dracul.example.com/api/strigoi-echo/complete",
                "tok-strigoi-echo", null, null, null);
        when(client.getAgent("strigoi-echo")).thenReturn(Optional.of(existing));

        registrar.registerAll();

        verify(client).updateAgent(eq("strigoi-echo"), any(UpdateAgentRequest.class));
        verify(client, never()).registerAgent(any());
    }

    @Test
    void maxRunSecondsChangeTriggersUpdate() {
        var client = mock(VistierieClient.class);
        var registrar = newRegistrar(client);
        var def = echo();
        var desired = registrar.buildRequest(def);

        // AgentDetail matching desired in all fields EXCEPT max_run_seconds (600 vs 1800)
        var existing = new AgentDetail(
                "id-1", "strigoi-echo",
                desired.system_prompt(),  // same as desired (includes language directive)
                "routine",
                desired.tools(),  // same tools as desired
                desired.output_schema(),
                25, 600, false, 1,  // max_run_seconds=600 differs from 1800
                Instant.EPOCH, Instant.EPOCH,
                "0 0 7 * * *", null,
                "https://dracul.example.com/api/strigoi-echo/complete",
                "tok-strigoi-echo", null, null, null);
        when(client.getAgent("strigoi-echo")).thenReturn(Optional.of(existing));

        registrar.registerAll();

        verify(client).updateAgent(eq("strigoi-echo"), any(UpdateAgentRequest.class));
        verify(client, never()).registerAgent(any());
    }

    @Test
    void streamingFieldChangeTriggersUpdate() {
        var client = mock(VistierieClient.class);
        var registrar = newStreamingRegistrar(client);
        var def = streamingEcho();
        var desired = registrar.buildRequest(def);

        // AgentDetail matching desired in all fields EXCEPT session_duration_seconds (1800 vs 3600)
        var existing = new AgentDetail(
                "id-1", "strigoi-stream",
                desired.system_prompt(),
                "routine",
                desired.tools(),
                desired.output_schema(),
                25, 1800, false, 1,
                Instant.EPOCH, Instant.EPOCH,
                null, null,
                desired.completion_webhook(), desired.completion_webhook_token(),
                desired.event_source_url(), 1800, desired.poll_interval_seconds());
        when(client.getAgent("strigoi-stream")).thenReturn(Optional.of(existing));

        registrar.registerAll();

        verify(client).updateAgent(eq("strigoi-stream"), any(UpdateAgentRequest.class));
        verify(client, never()).registerAgent(any());
    }

    @Test
    void streamingFieldsUnchangedIsUpToDate() {
        var client = mock(VistierieClient.class);
        var registrar = newStreamingRegistrar(client);
        var def = streamingEcho();
        var desired = registrar.buildRequest(def);

        var existing = new AgentDetail(
                "id-1", "strigoi-stream",
                desired.system_prompt(),
                "routine",
                desired.tools(),
                desired.output_schema(),
                25, 1800, false, 1,
                Instant.EPOCH, Instant.EPOCH,
                null, null,
                desired.completion_webhook(), desired.completion_webhook_token(),
                desired.event_source_url(), desired.session_duration_seconds(), desired.poll_interval_seconds());
        when(client.getAgent("strigoi-stream")).thenReturn(Optional.of(existing));

        registrar.registerAll();

        verify(client, never()).updateAgent(any(), any());
        verify(client, never()).registerAgent(any());
    }
}
