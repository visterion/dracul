package de.visterion.dracul.agent;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class AgentDefinitionStoreTest {

    @Autowired AgentDefinitionStore store;

    private AgentDefinition sample(String name) {
        var schema = JsonMapper.builder().build().createObjectNode().put("type", "object");
        return new AgentDefinition(name, "routine", "PROMPT-" + name, schema,
                "0 0 7 * * *", 25, 1800, "/api/" + name + "/complete",
                null, null, null, true,
                List.of(new ToolBinding("fetch_x", "override", null, 0)));
    }

    @Test
    void upsertIfAbsentThenLoad() {
        String name = "test-" + UUID.randomUUID();
        boolean inserted = store.insertIfAbsent(sample(name));
        boolean insertedAgain = store.insertIfAbsent(sample(name));

        assertThat(inserted).isTrue();
        assertThat(insertedAgain).isFalse();

        var loaded = store.find(name).orElseThrow();
        assertThat(loaded.promptText()).isEqualTo("PROMPT-" + name);
        assertThat(loaded.tools()).singleElement()
                .satisfies(t -> assertThat(t.toolName()).isEqualTo("fetch_x"));
    }

    @Test
    void saveOverwritesEditableFields() {
        String name = "test-" + UUID.randomUUID();
        store.insertIfAbsent(sample(name));
        var edited = sample(name);
        store.save(new AgentDefinition(name, "reasoning", "NEW PROMPT",
                edited.outputSchema(), "0 0 9 * * *", 8, 600, edited.completionPath(),
                null, null, null, false, edited.tools()));

        var loaded = store.find(name).orElseThrow();
        assertThat(loaded.promptText()).isEqualTo("NEW PROMPT");
        assertThat(loaded.modelPurpose()).isEqualTo("reasoning");
        assertThat(loaded.enabled()).isFalse();
    }

    @Test
    void findAllEnabledExcludesDisabled() {
        String on = "test-" + UUID.randomUUID();
        String off = "test-" + UUID.randomUUID();
        store.insertIfAbsent(sample(on));
        var disabled = sample(off);
        store.insertIfAbsent(new AgentDefinition(off, "routine", "P", disabled.outputSchema(),
                null, 25, 1800, "/c", null, null, null, false, List.of()));

        var enabledNames = store.findAllEnabled().stream().map(AgentDefinition::name).toList();
        assertThat(enabledNames).contains(on).doesNotContain(off);
    }
}
