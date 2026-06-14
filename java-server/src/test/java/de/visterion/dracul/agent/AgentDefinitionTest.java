package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionTest {

    @Test
    void scheduledAgentHasNoEventSource() {
        var def = new AgentDefinition(
                "strigoi-echo", "routine", "PROMPT",
                JsonMapper.builder().build().createObjectNode(),
                "0 0 7 * * *", 25, 1800,
                "/api/strigoi-echo/complete", null, null, null, true,
                List.of(new ToolBinding("fetch_recent_pead_candidates", null, null, 0)));
        assertThat(def.isStreaming()).isFalse();
        assertThat(def.tools()).hasSize(1);
    }

    @Test
    void streamingAgentReportsStreaming() {
        var def = new AgentDefinition(
                "daywalker", "reasoning", "PROMPT",
                JsonMapper.builder().build().createObjectNode(),
                "0 0 9 * * *", 8, 600,
                "/api/daywalker/complete", "/api/daywalker/events", 23400, 300, true,
                List.of());
        assertThat(def.isStreaming()).isTrue();
    }
}
