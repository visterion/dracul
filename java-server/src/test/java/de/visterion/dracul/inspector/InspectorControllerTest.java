package de.visterion.dracul.inspector;

import de.visterion.dracul.vistierie.RunSearchHit;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InspectorControllerTest {

    @Test
    void runsListReturnsMappedRuns() {
        var v = mock(VistierieClient.class);
        when(v.listAgentRuns(null, 50, 0)).thenReturn(List.of(
                new RunSearchHit("R1", "strigoi-echo", "done", false,
                        Instant.parse("2026-07-18T00:00:00Z"), 0, "found AAPL")));
        var c = new InspectorController(v);
        var res = c.runs(null, 50, 0);
        assertThat(res.runs()).hasSize(1);
        assertThat(res.runs().get(0).agent()).isEqualTo("strigoi-echo");
    }

    @Test
    void transcriptExpiredWhenNull() {
        var v = mock(VistierieClient.class);
        when(v.getRunTranscript("gone", "full")).thenReturn(null);
        var c = new InspectorController(v);
        var res = c.transcript("gone");
        assertThat(res.expired()).isTrue();
        assertThat(res.transcript()).isNull();
    }

    @Test
    void transcriptReturnsNode() {
        var v = mock(VistierieClient.class);
        JsonNode n = new ObjectMapper().createObjectNode().put("view", "full");
        when(v.getRunTranscript("R1", "full")).thenReturn(n);
        var c = new InspectorController(v);
        assertThat(c.transcript("R1").expired()).isFalse();
    }
}
