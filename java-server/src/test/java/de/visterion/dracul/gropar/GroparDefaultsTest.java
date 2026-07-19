package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.AgentDefaultProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GroparDefaultsTest {

    @Test
    void providerDefinitionIsWellFormed() {
        var defaults = new GroparDefaults();
        AgentDefaultProvider p = defaults.groparDefaultProvider(
                JsonMapper.builder().build(), "0 0 22 * * 1-5");
        var def = p.defaultDefinition();
        assertThat(def.name()).isEqualTo("gropar");
        assertThat(def.modelPurpose()).isEqualTo("reasoning");
        assertThat(def.isStreaming()).isFalse();
        assertThat(def.completionPath()).isEqualTo("/api/gropar/complete");
        assertThat(def.promptText()).isNotBlank();
        assertThat(def.outputSchema()).isNotNull();
        assertThat(def.tools()).extracting(de.visterion.dracul.agent.ToolBinding::toolName)
                .containsExactly("fetch_held_positions", "search");
        assertThat(p.catalogEntries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.toolName()).isEqualTo("fetch_held_positions");
                    assertThat(e.callbackPath()).isEqualTo("/api/gropar/tools/fetch-held-positions");
                });
    }
}
