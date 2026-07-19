package de.visterion.dracul.agent;

import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.vistierie.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 5: mcp branch in {@code buildRequest} + fail-fast for a blank HiveMem read-token. */
class GenericAgentRegistrarMcpTest {

    private static final String BASE_URL = "http://hivemem:8421";
    private static final String READ_TOKEN = "read-tok";

    private final JsonMapper json = JsonMapper.builder().build();

    private AgentDefinition withSearchOnly() {
        return new AgentDefinition("strigoi-searcher", "routine", "BASE PROMPT",
                json.createObjectNode().put("type", "object"),
                "0 0 7 * * *", 25, 1800, "/api/strigoi-searcher/complete",
                null, null, null, true,
                List.of(new ToolBinding("search", "desc", null, 0)));
    }

    private AgentDefinition withFetchAndSearch() {
        return new AgentDefinition("strigoi-both", "routine", "BASE PROMPT",
                json.createObjectNode().put("type", "object"),
                "0 0 7 * * *", 25, 1800, "/api/strigoi-both/complete",
                null, null, null, true,
                List.of(new ToolBinding("fetch_recent_pead_candidates", "fetch desc", null, 0),
                        new ToolBinding("search", "search desc", null, 1)));
    }

    private AgentToolCatalog catalogWithSearchAndFetch() {
        var fetchEntry = new ToolCatalogEntry("fetch_recent_pead_candidates", "catalog fetch desc",
                json.createObjectNode(), "/api/strigoi-both/tools/fetch-candidates", 30);
        var searchEntry = new ToolCatalogEntry("search", "catalog search desc",
                json.createObjectNode(), "/tools/search", 30);
        AgentDefaultProvider stub = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return null; }
            @Override public List<ToolCatalogEntry> catalogEntries() {
                return List.of(fetchEntry, searchEntry);
            }
        };
        return new AgentToolCatalog(List.of(stub));
    }

    private GenericAgentRegistrar registrar(List<AgentDefaultProvider> providers, String readToken) {
        var store = mock(AgentDefinitionStore.class);
        var settings = mock(AppSettingsRepository.class);
        when(settings.getLanguage()).thenReturn("en");
        return new GenericAgentRegistrar(mock(VistierieClient.class), store,
                catalogWithSearchAndFetch(), settings, "https://dracul.example.com",
                name -> "tok-" + name, providers, json, BASE_URL, readToken);
    }

    @Test
    void mcpBindingEmitsMcpToolDefAndCredentials() {
        var reg = registrar(List.of(), READ_TOKEN);
        var desired = reg.buildRequest(withSearchOnly());

        assertThat(desired.tools()).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("mcp");
            assertThat(t.webhook_url()).isNull();
            assertThat(t.mcp_server_url()).isEqualTo(BASE_URL);
            assertThat(t.mcp_tool_name()).isEqualTo("search");
        });

        assertThat(desired.mcp_credentials()).isNotNull();
        assertThat(desired.mcp_credentials().path(BASE_URL).asText()).isEqualTo(READ_TOKEN);
        // key byte-exact equal to mcp_server_url
        assertThat(desired.mcp_credentials().propertyNames())
                .containsExactly(desired.tools().get(0).mcp_server_url());
    }

    @Test
    void mcpCredentialsAbsentWhenNoMcpBinding() {
        var reg = registrar(List.of(), READ_TOKEN);
        var def = new AgentDefinition("strigoi-http-only", "routine", "BASE PROMPT",
                json.createObjectNode().put("type", "object"),
                "0 0 7 * * *", 25, 1800, "/api/strigoi-http-only/complete",
                null, null, null, true,
                List.of(new ToolBinding("fetch_recent_pead_candidates", "fetch desc", null, 0)));

        var desired = reg.buildRequest(def);

        assertThat(desired.mcp_credentials()).isNull();
        assertThat(desired.tools()).singleElement().satisfies(t -> {
            assertThat(t.type()).isNull();
            assertThat(t.mcp_server_url()).isNull();
            assertThat(t.mcp_tool_name()).isNull();
            assertThat(t.webhook_url())
                    .isEqualTo("https://dracul.example.com/api/strigoi-both/tools/fetch-candidates");
        });
    }

    @Test
    void httpToolsRemainByteIdenticalToPreExistingBehavior() {
        var reg = registrar(List.of(), READ_TOKEN);
        var def = withFetchAndSearch();

        var desired = reg.buildRequest(def);

        assertThat(desired.tools()).hasSize(2);
        var fetchTool = desired.tools().get(0);
        assertThat(fetchTool.name()).isEqualTo("fetch_recent_pead_candidates");
        assertThat(fetchTool.type()).isNull();
        assertThat(fetchTool.target_agent()).isNull();
        assertThat(fetchTool.webhook_url())
                .isEqualTo("https://dracul.example.com/api/strigoi-both/tools/fetch-candidates");
        assertThat(fetchTool.webhook_timeout_seconds()).isEqualTo(30);
        assertThat(fetchTool.mcp_server_url()).isNull();
        assertThat(fetchTool.mcp_tool_name()).isNull();
        assertThat(fetchTool.mcp_timeout_seconds()).isNull();
    }

    @Test
    void twoBindingsPreserveOrdinalOrder() {
        var reg = registrar(List.of(), READ_TOKEN);
        var desired = reg.buildRequest(withFetchAndSearch());

        assertThat(desired.tools()).extracting(ToolDef::name)
                .containsExactly("fetch_recent_pead_candidates", "search");
    }

    @Test
    void matchesIsStableAcrossTwoConsecutiveBuilds() {
        var reg = registrar(List.of(), READ_TOKEN);
        var def = withFetchAndSearch();
        var firstDesired = reg.buildRequest(def);

        // Simulate what Vistierie would echo back after the first registration.
        var existing = new AgentDetail(
                "id-1", "strigoi-both", firstDesired.system_prompt(), "routine",
                firstDesired.tools(), firstDesired.output_schema(),
                25, 1800, false, 1,
                Instant.EPOCH, Instant.EPOCH,
                "0 0 7 * * *", null,
                "https://dracul.example.com/api/strigoi-both/complete",
                "tok-strigoi-both", null, null, null);

        var client = mock(VistierieClient.class);
        when(client.getAgent("strigoi-both")).thenReturn(Optional.of(existing));
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(def));
        var settings = mock(AppSettingsRepository.class);
        when(settings.getLanguage()).thenReturn("en");
        var stableReg = new GenericAgentRegistrar(client, store, catalogWithSearchAndFetch(),
                settings, "https://dracul.example.com", name -> "tok-" + name,
                List.of(), json, BASE_URL, READ_TOKEN);

        stableReg.registerAll();

        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).updateAgent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).registerAgent(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blankReadTokenWithEnabledMcpBindingFailsFast() {
        AgentDefaultProvider provider = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return withSearchOnly(); }
        };
        var reg = registrar(List.of(provider), "");

        assertThatThrownBy(reg::checkMcpTokenConfigured)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankReadTokenFailsFastEvenWithEmptyAgentDefinitionStore() {
        // Proves the check reads the injected `providers` (code-default beans),
        // NOT the AgentDefinitionStore — which is empty at config/PostConstruct
        // time on a fresh DB or right after a definition-reset.
        AgentDefaultProvider provider = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return withSearchOnly(); }
        };
        var store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of());
        var settings = mock(AppSettingsRepository.class);
        when(settings.getLanguage()).thenReturn("en");
        var reg = new GenericAgentRegistrar(mock(VistierieClient.class), store,
                catalogWithSearchAndFetch(), settings, "https://dracul.example.com",
                name -> "tok-" + name, List.of(provider), json, BASE_URL, "");

        assertThatThrownBy(reg::checkMcpTokenConfigured)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonBlankReadTokenWithMcpBindingDoesNotFail() {
        AgentDefaultProvider provider = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return withSearchOnly(); }
        };
        var reg = registrar(List.of(provider), READ_TOKEN);

        reg.checkMcpTokenConfigured();
        // no exception
    }

    @Test
    void blankReadTokenWithoutAnyMcpBindingDoesNotFail() {
        AgentDefaultProvider provider = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() {
                return new AgentDefinition("strigoi-http-only", "routine", "BASE PROMPT",
                        json.createObjectNode().put("type", "object"),
                        "0 0 7 * * *", 25, 1800, "/api/strigoi-http-only/complete",
                        null, null, null, true,
                        List.of(new ToolBinding("fetch_recent_pead_candidates", "d", null, 0)));
            }
        };
        var reg = registrar(List.of(provider), "");

        reg.checkMcpTokenConfigured();
        // no exception
    }
}
