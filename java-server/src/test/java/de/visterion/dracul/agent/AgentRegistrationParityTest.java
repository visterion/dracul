package de.visterion.dracul.agent;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior-preservation gate for the new generic pipeline.
 * <p>
 * {@link AgentDefinitionBootstrap} seeds the {@link AgentDefinitionStore};
 * {@link GenericAgentRegistrar#buildRequest(AgentDefinition)} is the sole
 * registration path now that the 8 legacy registrars have been deleted.
 * <p>
 * For every enabled agent definition this test compares the current
 * {@code buildRequest} output against a committed golden JSON fixture under
 * {@code src/test/resources/golden-agents/<name>.json}. The comparison uses
 * {@code JsonNode.equals()} — structural equality over the WHOLE payload —
 * so PostgreSQL jsonb key-order normalisation of {@code output_schema} is
 * tolerated (JSON object key ordering is insignificant per RFC 8259 §4).
 * All values, numbers, and arrays are still compared exactly.
 * <p>
 * To regenerate fixtures (e.g. after an intentional schema change):
 * {@code ./mvnw -Dgolden.write=true -Dtest=AgentRegistrationParityTest test}
 */
@SpringBootTest(properties = {
    "dracul.daywalker.enabled=true", "dracul.voievod.enabled=true",
    "dracul.strigoi.echo.enabled=true", "dracul.strigoi.lazarus.enabled=true",
    "dracul.strigoi.merger.enabled=true", "dracul.strigoi.index.enabled=true",
    "dracul.strigoi.insider.enabled=true", "dracul.strigoi.spin.enabled=true",
    "dracul.renfield.enabled=true",
    "dracul.gropar.enabled=true",
    "dracul.voievod-outcome.enabled=true",
    "dracul.voievod-outcome.webhook-token=test-token",
    "dracul.voievod-outcome.schedule=0 0 7 * * 6"
})
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class AgentRegistrationParityTest {

    // Keeps the context bootable without a real Vistierie; GenericAgentRegistrar
    // calls getAgent/registerAgent on this mock at startup — harmless.
    @MockitoBean VistierieClient vistierie;
    @Autowired AgentDefinitionStore store;
    @Autowired AgentDefinitionBootstrap bootstrap;
    @Autowired GenericAgentRegistrar registrar;
    @Autowired ObjectMapper mapper;

    // Die 8 memory-lesenden Agenten (T1.6): je [fetch_..., search] in dieser Reihenfolge.
    // AUSNAHME strigoi-echo: seit 2026-07-27 zusätzlich das Detail-Tool fetch_candidate_news
    // zwischen fetch und search (Spec „echo news index + detail call").
    private static final java.util.Map<String, java.util.List<String>> TOOLS_BY_AGENT = java.util.Map.of(
            "strigoi-echo", java.util.List.of(
                    "fetch_recent_pead_candidates", "fetch_candidate_news", "search"),
            "strigoi-lazarus", java.util.List.of("fetch_quality_at_low_candidates", "search"),
            "strigoi-insider", java.util.List.of("fetch_recent_clusters", "search"),
            "strigoi-index", java.util.List.of("fetch_index_reconstitution_events", "search"),
            "strigoi-merger", java.util.List.of("fetch_recent_merger_candidates", "search"),
            "strigoi-spin", java.util.List.of("fetch_recent_spinoff_candidates", "search"),
            "gropar", java.util.List.of("fetch_held_positions", "search"),
            "voievod", java.util.List.of("fetch_consensus_clusters", "search")
    );

    @Test
    void newPipelineMatchesFrozenGoldenForEveryAgent() throws Exception {
        bootstrap.seed(); // idempotent; ensures store is populated
        var defs = store.findAllEnabled();
        assertThat(defs).hasSizeGreaterThanOrEqualTo(8);

        // T1.6: the 8 mcp-consuming agents must each carry exactly [fetch_..., search] in that
        // order. Runs in BOTH normal and golden.write mode, so a stale/incomplete ToolBinding on
        // any *Defaults bean fails loudly instead of silently baking into a regenerated fixture.
        for (var def : defs) {
            var expected = TOOLS_BY_AGENT.get(def.name());
            if (expected != null) {
                assertThat(def.tools()).extracting(ToolBinding::toolName)
                        .as("agent %s must carry exactly %s in that order", def.name(), expected)
                        .containsExactlyElementsOf(expected);
            }
        }

        if (Boolean.getBoolean("golden.write")) {
            var dir = java.nio.file.Path.of("src/test/resources/golden-agents");
            java.nio.file.Files.createDirectories(dir);
            for (var def : defs) {
                var json = mapper.writeValueAsString(registrar.buildRequest(def));
                java.nio.file.Files.writeString(dir.resolve(def.name() + ".json"), json);
            }
        } else {
            // Structural fixture validation: compare the current buildRequest(def) output against
            // the committed golden JSON using JsonNode.equals(). This is structural equality over
            // the WHOLE payload, so PostgreSQL jsonb key-order normalization of output_schema is
            // tolerated (JSON object key ordering is insignificant per RFC 8259 §4). All values,
            // numbers, and arrays are still compared exactly. Each agent has 0–2 tools (the 8
            // mcp-consuming agents carry [fetch, search]; the rest keep their prior 0–1), so
            // tool-array order is deterministic and array comparison is reliable.
            for (var def : defs) {
                var fixturePath = java.nio.file.Path.of("src/test/resources/golden-agents", def.name() + ".json");
                assertThat(fixturePath.toFile().exists())
                        .as("Golden fixture missing for agent '%s' — run with -Dgolden.write=true to generate it", def.name())
                        .isTrue();
                var fixtureNode = mapper.readTree(java.nio.file.Files.readString(fixturePath));
                var actualNode  = mapper.readTree(mapper.writeValueAsString(registrar.buildRequest(def)));
                assertThat(actualNode)
                        .as("Golden fixture drift detected for agent '%s' — buildRequest output no longer matches committed fixture", def.name())
                        .isEqualTo(fixtureNode);
            }
        }
    }
}
