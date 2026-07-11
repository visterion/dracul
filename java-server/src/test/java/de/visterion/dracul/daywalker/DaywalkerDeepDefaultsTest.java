package de.visterion.dracul.daywalker;

import de.visterion.dracul.agent.AgentDefaultProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DaywalkerDeepDefaultsTest {

    @Test
    void providerDefinitionIsWellFormed() {
        var defaults = new DaywalkerDeepDefaults();
        AgentDefaultProvider p = defaults.daywalkerDeepDefaultProvider(JsonMapper.builder().build());
        var def = p.defaultDefinition();

        assertThat(def.name()).isEqualTo("daywalker-deep");
        assertThat(def.modelPurpose()).isEqualTo("reasoning");
        assertThat(def.isStreaming()).isFalse();
        assertThat(def.schedule()).isNull(); // trigger-only, never scheduled
        assertThat(def.maxTurns()).isEqualTo(4);
        assertThat(def.maxRunSeconds()).isEqualTo(300);
        assertThat(def.completionPath()).isEqualTo("/api/daywalker-deep/complete");
        assertThat(def.promptText()).isNotBlank();
        assertThat(def.outputSchema()).isNotNull();
        assertThat(def.tools()).isEmpty();
        assertThat(p.catalogEntries()).isEmpty();
    }
}
