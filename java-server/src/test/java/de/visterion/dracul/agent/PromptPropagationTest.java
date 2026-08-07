package de.visterion.dracul.agent;

import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.vistierie.AgentDetail;
import de.visterion.dracul.vistierie.CreateAgentRequest;
import de.visterion.dracul.vistierie.UpdateAgentRequest;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the whole path a bundled prompt change has to travel to reach a live agent:
 *
 * <pre>prompts/&lt;agent&gt;.md → *Defaults bean → agent_definition → GenericAgentRegistrar → Vistierie</pre>
 *
 * <p><b>The defect this class exists for.</b> The third hop did not exist. Seeding was
 * insert-if-absent only, so {@code agent_definition.prompt_text} froze at whatever was bundled the
 * day the row was written; the registrar built its request from that frozen row and therefore
 * agreed with Vistierie forever. Observed in production on 2026-08-06: gropar 1.3.0 was in the
 * image, the registrar logged "gropar registration is up-to-date", and the agent kept running
 * 1.2.0. {@link #staleStoreMakesTheRegistrarAgreeWithVistierie()} reproduces that agreement
 * directly, so the reason "up-to-date" was never evidence of propagation stays documented.
 *
 * <p>All fixtures are hand-written synthetic values or files from this repo's own resources.
 */
class PromptPropagationTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final PromptArchive archive = new PromptArchive();

    private static final String BUNDLED = PromptDocument.bodyFromClasspath("prompts/gropar.md");
    private static final String ARCHIVED_1_2_0 =
            PromptDocument.bodyFromClasspath("prompts/archive/gropar/1.2.0.md");

    private AgentDefinition gropar(String promptText) {
        return new AgentDefinition("gropar", "reasoning", promptText,
                json.createObjectNode().put("type", "object"),
                "0 0 7 * * *", 25, 1800, "/api/gropar/complete",
                null, null, null, true, List.of());
    }

    private AgentDefinitionBootstrap bootstrap(AgentDefinitionStore store, AgentDefinition bundled) {
        AgentDefaultProvider provider = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return bundled; }
            @Override public List<ToolCatalogEntry> catalogEntries() { return List.of(); }
        };
        return new AgentDefinitionBootstrap(List.of(provider), store, archive);
    }

    private GenericAgentRegistrar registrar(VistierieClient client, AgentDefinitionStore store) {
        var settings = mock(AppSettingsRepository.class);
        when(settings.getLanguage()).thenReturn("en");
        return new GenericAgentRegistrar(client, store, new AgentToolCatalog(List.of()), settings,
                "https://dracul.example.com", name -> "tok-" + name,
                List.of(), json, "http://hivemem:8421", "read-tok");
    }

    private AgentDetail detailFrom(CreateAgentRequest desired) {
        return new AgentDetail("id-gropar", "gropar", desired.system_prompt(), "reasoning",
                desired.tools(), desired.output_schema(), 25, 1800, false, 1,
                Instant.EPOCH, Instant.EPOCH, desired.schedule(), null,
                desired.completion_webhook(), desired.completion_webhook_token(),
                null, null, null);
    }

    // ---------------------------------------------------------------- regression

    /**
     * The exact production failure. With a stale stored prompt, the registrar's comparison is
     * perfectly satisfied — store and Vistierie really do agree — so "up-to-date" is logged while
     * the bundled prompt is not in effect. The registrar is not the broken component; its input is.
     */
    @Test
    void staleStoreMakesTheRegistrarAgreeWithVistierie() {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(gropar(ARCHIVED_1_2_0)));
        var client = mock(VistierieClient.class);
        var reg = registrar(client, store);
        // Vistierie holds exactly what the stale store produces.
        // NB: build the detail BEFORE when(...) — buildRequest touches mocks itself.
        var stale = detailFrom(reg.buildRequest(gropar(ARCHIVED_1_2_0)));
        when(client.getAgent("gropar")).thenReturn(Optional.of(stale));

        reg.registerAll();

        verify(client, never()).updateAgent(any(), any());
        verify(client, never()).registerAgent(any());
        assertThat(ARCHIVED_1_2_0)
                .as("the stale body genuinely lacks the 1.3.0 text — the agreement is the bug")
                .isNotEqualTo(BUNDLED);
    }

    // ---------------------------------------------------------------- propagation

    /** Hop 3: a changed bundled prompt overwrites a stale (never-edited) stored prompt. */
    @Test
    void bundledPromptChangeReconcilesAStaleStoredPrompt() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("gropar")).thenReturn(Optional.of(gropar(ARCHIVED_1_2_0)));

        bootstrap(store, gropar(BUNDLED)).seed();

        verify(store).updatePromptText("gropar", BUNDLED);
    }

    /** Hop 4: the reconciled prompt actually reaches Vistierie as an update request. */
    @Test
    void reconciledPromptReachesTheUpdateRequest() {
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(gropar(BUNDLED)));
        var client = mock(VistierieClient.class);
        var reg = registrar(client, store);
        // Vistierie still holds the pre-bump prompt.
        // NB: build the detail BEFORE when(...) — buildRequest touches mocks itself.
        var stale = detailFrom(reg.buildRequest(gropar(ARCHIVED_1_2_0)));
        when(client.getAgent("gropar")).thenReturn(Optional.of(stale));

        reg.registerAll();

        var captor = ArgumentCaptor.forClass(UpdateAgentRequest.class);
        verify(client).updateAgent(eq("gropar"), captor.capture());
        assertThat(captor.getValue().system_prompt())
                .startsWith(BUNDLED)
                .contains("currentPriceAvailable");
    }

    /** Reconcile must be idempotent: an already-current store is left completely alone. */
    @Test
    void anAlreadyCurrentStoredPromptIsNotRewritten() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("gropar")).thenReturn(Optional.of(gropar(BUNDLED)));

        bootstrap(store, gropar(BUNDLED)).seed();

        verify(store, never()).updatePromptText(any(), any());
    }

    /** A freshly inserted row is already current; no reconcile pass is attempted. */
    @Test
    void aFreshlySeededRowIsNotReconciled() {
        var store = mock(AgentDefinitionStore.class);
        when(store.insertIfAbsent(any())).thenReturn(true);

        bootstrap(store, gropar(BUNDLED)).seed();

        verify(store, never()).updatePromptText(any(), any());
        verify(store, never()).find(any());
    }

    // ---------------------------------------------------------------- operator edit

    /** An operator edit is never destroyed by a redeploy — the other half of the contract. */
    @Test
    void anOperatorEditIsPreservedNotOverwritten() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("gropar"))
                .thenReturn(Optional.of(gropar("SYNTHETIC OPERATOR EDIT — hand typed in the UI")));

        bootstrap(store, gropar(BUNDLED)).seed();

        verify(store, never()).updatePromptText(any(), any());
        verify(store, never()).save(any());
    }

    /**
     * Reconcile uses the narrow {@code updatePromptText}, never {@code save}: an operator who
     * retuned schedule/turns keeps those even while the prompt body is brought up to date.
     */
    @Test
    void reconcileTouchesOnlyThePromptBody() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find("gropar")).thenReturn(Optional.of(gropar(ARCHIVED_1_2_0)));

        bootstrap(store, gropar(BUNDLED)).seed();

        verify(store).updatePromptText("gropar", BUNDLED);
        verify(store, never()).save(any());
    }
}
