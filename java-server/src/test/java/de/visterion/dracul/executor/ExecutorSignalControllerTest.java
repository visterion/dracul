package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExecutorSignalControllerTest {

    @Test
    void injectGeneratesIdAndPersistsPending() throws Exception {
        var repo = mock(ExecutorSignalRepository.class);
        var controller = new ExecutorSignalController(repo);

        String json = """
                {
                  "symbol": "ACME",
                  "direction": "LONG",
                  "confidence": 0.8,
                  "kill_criteria": ["EARNINGS_MISS"],
                  "reference_price": 100.5
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.inject(body);

        var captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(repo).insert(captor.capture());
        var signal = captor.getValue();

        assertThat(signal.symbol()).isEqualTo("ACME");
        assertThat(signal.direction()).isEqualTo("LONG");
        assertThat(signal.confidence()).isEqualTo(0.8);
        assertThat(signal.killCriteria()).containsExactly("EARNINGS_MISS");
        assertThat(signal.referencePrice()).isEqualByComparingTo(new BigDecimal("100.5"));
        assertThat(signal.status()).isEqualTo("PENDING");
        assertThat(signal.source()).isEqualTo("injected");
        assertThat(signal.agentVersion()).isEqualTo("operator");

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = (Map<String, Object>) resp.getBody();
        assertThat(responseBody).isNotNull();
        assertThat((String) responseBody.get("signal_id")).isNotBlank();
        assertThat(responseBody.get("status")).isEqualTo("PENDING");
    }

    @Test
    void injectDefaultsAgentVersionToOperatorWhenBlank() throws Exception {
        var repo = mock(ExecutorSignalRepository.class);
        var controller = new ExecutorSignalController(repo);

        String json = """
                {
                  "symbol": "ACME",
                  "direction": "LONG",
                  "agent_version": ""
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        controller.inject(body);

        var captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(repo).insert(captor.capture());
        assertThat(captor.getValue().agentVersion()).isEqualTo("operator");
    }

    @Test
    void injectKeepsProvidedAgentVersion() throws Exception {
        var repo = mock(ExecutorSignalRepository.class);
        var controller = new ExecutorSignalController(repo);

        String json = """
                {
                  "symbol": "ACME",
                  "direction": "LONG",
                  "agent_version": "v7"
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        controller.inject(body);

        var captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(repo).insert(captor.capture());
        assertThat(captor.getValue().agentVersion()).isEqualTo("v7");
    }

    @Test
    void injectKeepsProvidedSignalId() throws Exception {
        var repo = mock(ExecutorSignalRepository.class);
        var controller = new ExecutorSignalController(repo);

        String json = """
                {
                  "signal_id": "sig-42",
                  "symbol": "ACME",
                  "direction": "LONG"
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.inject(body);

        var captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(repo).insert(captor.capture());
        assertThat(captor.getValue().signalId()).isEqualTo("sig-42");

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = (Map<String, Object>) resp.getBody();
        assertThat(responseBody.get("signal_id")).isEqualTo("sig-42");
    }

    @Test
    void injectHandlesMissingOptionalFields() throws Exception {
        var repo = mock(ExecutorSignalRepository.class);
        var controller = new ExecutorSignalController(repo);

        String json = """
                {
                  "symbol": "ACME",
                  "direction": "LONG",
                  "confidence": 0.5
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        controller.inject(body);

        var captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(repo).insert(captor.capture());
        var signal = captor.getValue();

        assertThat(signal.killCriteria()).isEmpty();
        assertThat(signal.referencePrice()).isNull();
        assertThat(signal.confidence()).isEqualTo(0.5);
        assertThat(signal.agentVersion()).isEqualTo("operator");
    }

    @Test
    void pendingDelegatesToRepo() {
        var repo = mock(ExecutorSignalRepository.class);
        var controller = new ExecutorSignalController(repo);

        var s = new ExecutorSignal("id-1", "injected", null, "ACME", "LONG", 0.7,
                null, List.of(), null, null, "PENDING", null);
        when(repo.findPending(50)).thenReturn(List.of(s));

        var result = controller.pending();

        assertThat(result).containsExactly(s);
        verify(repo).findPending(50);
    }
}
