package de.visterion.dracul.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutorWebhookControllerTest {

    private static final String BEARER = "Bearer tkn";

    private ExecutorSignalRepository signalRepo;
    private ExecutorPositionRepository positionRepo;
    private ExecutorDecisionRepository decisionRepo;
    private AgoraTrading agoraTrading;
    private ExecutorIndicators executorIndicators;
    private JsonMapper mapper;

    private ExecutorWebhookController controller;

    @BeforeEach
    void setUp() {
        signalRepo = mock(ExecutorSignalRepository.class);
        positionRepo = mock(ExecutorPositionRepository.class);
        decisionRepo = mock(ExecutorDecisionRepository.class);
        agoraTrading = mock(AgoraTrading.class);
        executorIndicators = mock(ExecutorIndicators.class);
        mapper = JsonMapper.builder().build();

        when(executorIndicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());

        controller = new ExecutorWebhookController(
                signalRepo, positionRepo, decisionRepo,
                new VetoService(), new OrderGuard(), agoraTrading, executorIndicators, mapper,
                "tkn", "saxo-sim", 0.6, 3, 22, 20);
    }

    private ExecutorSignal signal(String signalId, double confidence, BigDecimal referencePrice) {
        return signal(signalId, confidence, referencePrice, "PENDING");
    }

    private ExecutorSignal signal(String signalId, double confidence, BigDecimal referencePrice, String status) {
        return new ExecutorSignal(signalId, "hunter", "v1", "ACME", "LONG",
                confidence, "mechanism", List.of("X"), "3m", referencePrice,
                status, "2026-07-01T00:00:00Z");
    }

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputOf(ResponseEntity<?> resp) {
        return (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
    }

    // -------------------------------------------------------------------
    // auth
    // -------------------------------------------------------------------

    @Test
    void authRejected() {
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","qty":10,"stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry("Bearer wrong", null, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(agoraTrading, signalRepo, positionRepo, decisionRepo);
    }

    // -------------------------------------------------------------------
    // place-entry: veto rejections — NO broker call
    // -------------------------------------------------------------------

    @Test
    void placeEntry_lowConfidence_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.4, new BigDecimal("100")));
        when(positionRepo.countOpen()).thenReturn(0);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","qty":10,"stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("LOW_CONFIDENCE");

        verify(agoraTrading, never()).placeBracket(any(), any(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo).markStatus("sig-1", "REJECTED");

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void placeEntry_maxPositions_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(positionRepo.countOpen()).thenReturn(3);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","qty":10,"stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("MAX_POSITIONS");

        verify(agoraTrading, never()).placeBracket(any(), any(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).insert(any());
    }

    @Test
    void placeEntry_schemaInvalid_unknownSignal() {
        when(signalRepo.findById("ghost")).thenReturn(null);

        JsonNode body = json("""
                {"signal_id":"ghost","symbol":"ZZZ","side":"BUY","qty":10,"stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");

        verify(agoraTrading, never()).placeBracket(any(), any(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(eq("ghost"), eq("ACCEPTED"));

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("SCHEMA_INVALID");
    }

    @Test
    void placeEntry_alreadyProcessed_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100"), "ACCEPTED"));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","qty":10,"stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("DUPLICATE");

        verify(agoraTrading, never()).placeBracket(any(), any(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(anyString(), anyString());

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("DUPLICATE");
    }

    @Test
    void placeEntry_orderGuardWrongStop_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(positionRepo.countOpen()).thenReturn(0);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","qty":10,"stop_price":105}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NO_STOP");

        verify(agoraTrading, never()).placeBracket(any(), any(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo).markStatus("sig-1", "REJECTED");

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("NO_STOP");
    }

    @Test
    void placeEntry_nullBody_rejectsCleanly() {
        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");

        verify(agoraTrading, never()).placeBracket(any(), any(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).insert(any());
    }

    // -------------------------------------------------------------------
    // place-entry: happy path
    // -------------------------------------------------------------------

    @Test
    void placeEntry_happyPath_placesAndBooks() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(positionRepo.countOpen()).thenReturn(0);
        when(agoraTrading.placeBracket(anyString(), anyString(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(json("{\"broker_order_id\":\"ord-9\"}"));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","qty":10,"stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("ord-9");
        assertThat(output.get("position_id")).isEqualTo(77L);

        verify(agoraTrading, times(1)).placeBracket(eq("saxo-sim"), eq("ACME"), eq("BUY"),
                eq(new BigDecimal("10")), isNull(), eq(new BigDecimal("95")), isNull());

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().status()).isEqualTo("OPEN");
        assertThat(posCaptor.getValue().brokerOrderId()).isEqualTo("ord-9");

        verify(signalRepo).markStatus("sig-1", "ACCEPTED");

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isTrue();
        assertThat(decCaptor.getValue().brokerOrderId()).isEqualTo("ord-9");
    }

    @Test
    void placeEntry_brokerError_noPositionBooked() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(positionRepo.countOpen()).thenReturn(0);
        when(agoraTrading.placeBracket(anyString(), anyString(), anyString(),
                any(), any(), any(), any()))
                .thenThrow(new AgoraTradingException("broker down"));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","qty":10,"stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(positionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus("sig-1", "ACCEPTED");

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("BROKER_ERROR");
    }

    // -------------------------------------------------------------------
    // fetch-pending-signals
    // -------------------------------------------------------------------

    @Test
    void fetchPending_serializesShape() {
        when(signalRepo.findPending(50)).thenReturn(List.of(signal("sig-1", 0.8, new BigDecimal("100"))));

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        Map<String, Object> output = outputOf(resp);
        List<?> signals = (List<?>) output.get("signals");
        assertThat(signals).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) signals.get(0);
        assertThat(first.get("symbol")).isEqualTo("ACME");
        assertThat(first.get("confidence")).isEqualTo(0.8);
        assertThat(first.get("kill_criteria")).isEqualTo(List.of("X"));
    }

    @Test
    void fetchPending_enrichesWithIndicatorLevelsWhenAvailable() {
        when(signalRepo.findPending(50)).thenReturn(List.of(signal("sig-1", 0.8, new BigDecimal("100"))));
        when(executorIndicators.levels("ACME", 22, 20)).thenReturn(
                new ExecutorIndicators.Levels(true, new BigDecimal("2.5"), new BigDecimal("92"), new BigDecimal("100")));

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) outputOf(resp).get("signals")).get(0);
        assertThat(first.get("atr")).isEqualTo(new BigDecimal("2.5"));
        assertThat(first.get("swing_low")).isEqualTo(new BigDecimal("92"));
        assertThat(first.get("reference_price")).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void fetchPending_indicatorsUnavailable_fieldsNullWithoutError() {
        when(signalRepo.findPending(50)).thenReturn(List.of(signal("sig-1", 0.8, new BigDecimal("100"))));
        when(executorIndicators.levels("ACME", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) outputOf(resp).get("signals")).get(0);
        assertThat(first.get("atr")).isNull();
        assertThat(first.get("swing_low")).isNull();
    }

    // -------------------------------------------------------------------
    // get-account
    // -------------------------------------------------------------------

    @Test
    void getAccount_unavailableEnvelope() {
        when(agoraTrading.account("saxo-sim")).thenThrow(new AgoraTradingException("no session"));

        ResponseEntity<?> resp = controller.getAccount(BEARER, json("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("available")).isEqualTo(false);
        assertThat(output.get("error")).isEqualTo("no session");
    }

    @Test
    void getAccount_happy() {
        when(agoraTrading.account("saxo-sim")).thenReturn(json("{\"cash\":\"1000\"}"));

        ResponseEntity<?> resp = controller.getAccount(BEARER, json("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode output = (JsonNode) ((Map<?, ?>) resp.getBody()).get("output");
        assertThat(output.path("cash").asString("")).isEqualTo("1000");
    }

    // -------------------------------------------------------------------
    // submit-decision
    // -------------------------------------------------------------------

    @Test
    void submitDecision_recordsSkips() {
        JsonNode body = json("""
                {
                  "decisions": [
                    {"signal_id":"sig-1","symbol":"ACME","action":"SKIP","rationale":"thin"},
                    {"signal_id":"sig-2","symbol":"BBB","action":"ENTER","rationale":"x"}
                  ]
                }
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(1);

        verify(decisionRepo, times(1)).insert(any());
        verify(signalRepo).markStatus("sig-1", "SKIPPED");
        verify(signalRepo, never()).markStatus(eq("sig-2"), any());
    }

    @Test
    void submitDecision_nullBody_recordsZero() {
        ResponseEntity<?> resp = controller.submitDecision(BEARER, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(0);

        verify(decisionRepo, never()).insert(any());
    }

    // -------------------------------------------------------------------
    // complete
    // -------------------------------------------------------------------

    @Test
    void complete_returns204() {
        ResponseEntity<Void> resp = controller.complete(BEARER, "run-1", json("{\"status\":\"done\"}"));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void complete_authRejected() {
        ResponseEntity<Void> resp = controller.complete("Bearer wrong", "run-1", json("{\"status\":\"done\"}"));
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }
}
