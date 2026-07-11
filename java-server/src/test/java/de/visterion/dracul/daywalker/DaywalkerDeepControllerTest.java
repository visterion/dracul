package de.visterion.dracul.daywalker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerDeepControllerTest {

    private static final String BEARER = "Bearer tok";

    private DaywalkerCompletionService completionService;
    private DaywalkerDeepController controller;

    @BeforeEach
    void setUp() {
        completionService = mock(DaywalkerCompletionService.class);
        controller = new DaywalkerDeepController("tok", completionService);
    }

    @Test
    void complete_badToken_returns401() throws Exception {
        JsonNode body = JsonMapper.builder().build().readTree("""
                {"status":"done","output":{}}
                """);

        var resp = controller.complete("Bearer wrong", "run-1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(completionService);
    }

    @Test
    void complete_missingToken_returns401() throws Exception {
        JsonNode body = JsonMapper.builder().build().readTree("""
                {"status":"done","output":{}}
                """);

        var resp = controller.complete(null, "run-1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(completionService);
    }

    @Test
    void complete_nonDoneStatus_acknowledgesWithoutPersisting() throws Exception {
        JsonNode body = JsonMapper.builder().build().readTree("""
                {"status":"failed","output":{}}
                """);

        var resp = controller.complete(BEARER, "run-2", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(completionService);
    }

    @Test
    void complete_missingSymbolOrTriggerType_acknowledgesWithoutPersisting() throws Exception {
        JsonNode body = JsonMapper.builder().build().readTree("""
                {"status":"done","output":{"severity":"WARNING","thesis":"x","confidence":0.7}}
                """);

        var resp = controller.complete(BEARER, "run-3", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(completionService);
    }

    @Test
    void complete_doneStatus_feedsPersistAssessmentWithFromEscalationTrue() throws Exception {
        String json = """
                {
                  "status": "done",
                  "output": {
                    "symbol": "AAPL",
                    "trigger_type": "PRICE_SPIKE",
                    "severity": "WARNING",
                    "thesis": "revised thesis after deeper scrutiny",
                    "confidence": 0.75
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-4", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(completionService).persistAssessment(
                eq("AAPL"), eq("PRICE_SPIKE"), eq("WARNING"),
                eq("revised thesis after deeper scrutiny"), eq(new BigDecimal("0.75")),
                eq("run-4"), isNull(), eq(true));
    }

    @Test
    void complete_succeededStatus_alsoPersists() throws Exception {
        String json = """
                {
                  "status": "succeeded",
                  "output": {
                    "symbol": "MSFT",
                    "trigger_type": "VOLUME_SPIKE",
                    "severity": "CRITICAL",
                    "thesis": "confirmed after scrutiny",
                    "confidence": 0.85
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-5", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(completionService).persistAssessment(
                eq("MSFT"), eq("VOLUME_SPIKE"), eq("CRITICAL"),
                eq("confirmed after scrutiny"), eq(new BigDecimal("0.85")),
                eq("run-5"), isNull(), eq(true));
    }

    @Test
    void complete_withEchoedPositionId_passesItAsPositionIdArg() throws Exception {
        String json = """
                {
                  "status": "done",
                  "output": {
                    "symbol": "AAPL",
                    "trigger_type": "PRICE_SPIKE",
                    "severity": "CRITICAL",
                    "thesis": "stop breach confirmed",
                    "confidence": 0.9,
                    "position_id": "wid-1"
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-6", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(completionService).persistAssessment(
                eq("AAPL"), eq("PRICE_SPIKE"), eq("CRITICAL"),
                eq("stop breach confirmed"), eq(new BigDecimal("0.9")),
                eq("run-6"), eq("wid-1"), eq(true));
    }

    @Test
    void complete_withNullPositionId_passesNull() throws Exception {
        String json = """
                {
                  "status": "done",
                  "output": {
                    "symbol": "AAPL",
                    "trigger_type": "PRICE_SPIKE",
                    "severity": "WARNING",
                    "thesis": "watch-only reassessment",
                    "confidence": 0.7,
                    "position_id": null
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-7", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(completionService).persistAssessment(
                eq("AAPL"), eq("PRICE_SPIKE"), eq("WARNING"),
                eq("watch-only reassessment"), eq(new BigDecimal("0.7")),
                eq("run-7"), isNull(), eq(true));
    }
}
