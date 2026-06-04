package de.visterion.dracul.daywalker;

import de.visterion.dracul.vistierie.CreateAgentRequest;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DaywalkerRegistrarTest {

    @Test
    void buildRequestSetsStreamingFields() {
        var registrar = new DaywalkerRegistrar(
                mock(VistierieClient.class), new ObjectMapper(),
                "http://test.invalid:9090/", "tok",
                "0 30 13 * * 1-5", 23400, 300);

        CreateAgentRequest req = registrar.buildRequest();

        assertThat(req.name()).isEqualTo("daywalker");
        assertThat(req.model_purpose()).isEqualTo("reasoning");
        assertThat(req.event_source_url()).isEqualTo("http://test.invalid:9090/api/daywalker/events");
        assertThat(req.completion_webhook()).isEqualTo("http://test.invalid:9090/api/daywalker/complete");
        assertThat(req.session_duration_seconds()).isEqualTo(23400);
        assertThat(req.poll_interval_seconds()).isEqualTo(300);
        assertThat(req.schedule()).isEqualTo("0 30 13 * * 1-5");
        assertThat(req.tools()).isEmpty();
        assertThat(req.output_schema()).isNotNull();
    }
}
