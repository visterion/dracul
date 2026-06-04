package de.visterion.dracul.vistierie;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRequestStreamingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void streamingFieldsSerializeWhenSet() {
        var req = new CreateAgentRequest(
                "daywalker", "prompt", "reasoning", List.of(), null,
                8, 600, "tok", "0 30 13 * * 1-5",
                "http://x/complete", "tok",
                "http://x/events", 23400, 300);
        String json = mapper.writeValueAsString(req);
        assertThat(json).contains("\"event_source_url\":\"http://x/events\"");
        assertThat(json).contains("\"session_duration_seconds\":23400");
        assertThat(json).contains("\"poll_interval_seconds\":300");
    }

    @Test
    void backCompatConstructorLeavesStreamingFieldsNull() {
        var req = new CreateAgentRequest(
                "strigoi-echo", "prompt", "routine", List.of(), null,
                25, 1800, "tok", "0 0 22 * * 1-5",
                "http://x/complete", "tok");
        assertThat(req.event_source_url()).isNull();
        assertThat(req.session_duration_seconds()).isNull();
        assertThat(req.poll_interval_seconds()).isNull();
    }

    @Test
    void updateRequestBackCompatConstructorWorks() {
        var req = new UpdateAgentRequest(
                "prompt", "routine", List.of(), null,
                25, 1800, "tok", "0 0 22 * * 1-5",
                "http://x/complete", "tok");
        assertThat(req.event_source_url()).isNull();
    }
}
