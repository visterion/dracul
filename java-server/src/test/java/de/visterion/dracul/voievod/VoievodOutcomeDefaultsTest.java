package de.visterion.dracul.voievod;

import de.visterion.dracul.agent.AgentDefaultProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class VoievodOutcomeDefaultsTest {

    @Test
    void providerDefinitionIsWellFormed() {
        var defaults = new VoievodOutcomeDefaults();
        AgentDefaultProvider p = defaults.voievodOutcomeDefaultProvider(
                JsonMapper.builder().build(), "0 0 7 * * 6");
        var def = p.defaultDefinition();

        assertThat(def.name()).isEqualTo("voievod-outcome");
        assertThat(def.modelPurpose()).isEqualTo("reasoning");
        assertThat(def.isStreaming()).isFalse();
        assertThat(def.schedule()).isEqualTo("0 0 7 * * 6");
        assertThat(def.completionPath()).isEqualTo("/webhook/voievod-outcome/complete");
        assertThat(def.promptText()).isNotBlank();
        assertThat(def.outputSchema()).isNotNull();
        assertThat(def.tools()).singleElement()
                .satisfies(t -> assertThat(t.toolName()).isEqualTo("fetch_elapsed_prey"));
        assertThat(p.catalogEntries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.toolName()).isEqualTo("fetch_elapsed_prey");
                    assertThat(e.callbackPath()).isEqualTo("/webhook/voievod-outcome/tools/fetch-elapsed-prey");
                });
    }
}
