package de.visterion.dracul.agent;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.vistierie.CreateAgentRequest;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

/**
 * Behavior-preservation gate. At startup the legacy registrars AND the new
 * GenericAgentRegistrar both call registerAgent. We assert every captured payload
 * for an agent equals the new pipeline's buildRequest(def) — proving legacy == new
 * byte-for-byte. Then we freeze the payloads as golden fixtures (run with
 * -Dgolden.write=true). The legacy registrars are deleted in a later task, after
 * which this test is replaced by a fixture comparison.
 */
@SpringBootTest(properties = {
    "dracul.daywalker.enabled=true", "dracul.voievod.enabled=true",
    "dracul.strigoi.echo.enabled=true", "dracul.strigoi.lazarus.enabled=true",
    "dracul.strigoi.merger.enabled=true", "dracul.strigoi.index.enabled=true",
    "dracul.strigoi.insider.enabled=true", "dracul.strigoi.spin.enabled=true"
})
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class AgentRegistrationParityTest {

    @MockitoBean VistierieClient vistierie;
    @Autowired AgentDefinitionStore store;
    @Autowired AgentDefinitionBootstrap bootstrap;
    @Autowired GenericAgentRegistrar registrar;
    @Autowired ObjectMapper mapper;

    @Test
    void newPipelineMatchesLegacyForEveryAgent() throws Exception {
        // Capture every registerAgent fired at startup (legacy + new), grouped by name.
        var captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(vistierie, atLeast(8)).registerAgent(captor.capture());

        Map<String, List<CreateAgentRequest>> capturedByName = new HashMap<>();
        for (var req : captor.getAllValues()) {
            capturedByName.computeIfAbsent(req.name(), k -> new ArrayList<>()).add(req);
        }

        bootstrap.seed(); // idempotent; ensure store is populated
        var defs = store.findAllEnabled();
        assertThat(defs).hasSizeGreaterThanOrEqualTo(8);

        Map<String, String> frozen = new HashMap<>();
        for (var def : defs) {
            var expectedReq = registrar.buildRequest(def);
            // Normalize both sides via a full re-parse so that JSONB key-reordering
            // (an implementation detail of the store round-trip with no semantic impact)
            // does not produce false negatives. JSON object key ordering is insignificant
            // per RFC 8259 §4; JsonNode.equals() compares structurally (order-independent).
            var expectedNode = mapper.readTree(mapper.writeValueAsString(expectedReq));
            frozen.put(def.name(), mapper.writeValueAsString(expectedReq));
            var captured = capturedByName.get(def.name());
            assertThat(captured)
                    .as("legacy registration captured for %s", def.name())
                    .isNotEmpty();
            for (var c : captured) {
                var capturedNode = mapper.readTree(mapper.writeValueAsString(c));
                assertThat(capturedNode)
                        .as("payload parity for %s", def.name())
                        .isEqualTo(expectedNode);
            }
        }

        if (Boolean.getBoolean("golden.write")) {
            var dir = java.nio.file.Path.of("src/test/resources/golden-agents");
            java.nio.file.Files.createDirectories(dir);
            for (var e : frozen.entrySet()) {
                java.nio.file.Files.writeString(dir.resolve(e.getKey() + ".json"), e.getValue());
            }
        } else {
            // Structural fixture validation: compare the current buildRequest(def) output against
            // the committed golden JSON using JsonNode.equals(). This is structural equality over
            // the WHOLE payload, so PostgreSQL jsonb key-order normalization of output_schema is
            // tolerated (JSON object key ordering is insignificant per RFC 8259 §4). All values,
            // numbers, and arrays are still compared exactly. Each agent has 0–1 tools, so
            // tool-array order is deterministic and array comparison is reliable.
            for (var def : defs) {
                var fixturePath = java.nio.file.Path.of("src/test/resources/golden-agents", def.name() + ".json");
                assertThat(fixturePath.toFile().exists())
                        .as("Golden fixture missing for agent '%s' — run with -Dgolden.write=true to generate it", def.name())
                        .isTrue();
                var fixtureContent = java.nio.file.Files.readString(fixturePath);
                assertThat(mapper.readTree(fixtureContent))
                        .as("Golden fixture drift detected for agent '%s' — buildRequest output no longer matches committed fixture", def.name())
                        .isEqualTo(mapper.readTree(frozen.get(def.name())));
            }
        }
    }
}
