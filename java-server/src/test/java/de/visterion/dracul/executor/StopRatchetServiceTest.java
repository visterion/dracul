package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the ratchet only ever raises (BUY) stops and never lowers one — the guard must
 *  deny every non-improving move, proving zero down-moves end to end. */
class StopRatchetServiceTest {

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorNotifier executorNotifier = mock(ExecutorNotifier.class);

    private StopRatchetService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new StopRatchetService(gateway, positionRepo, decisionRepo, ruleVersions,
                new StopRatchetGuard(), mapper, executorNotifier, 3.0);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal highestPrice,
            BigDecimal activeStop, BigDecimal mfeR, int softConfirmCount) {
        return openPosition(id, symbol, side, highestPrice, activeStop, mfeR, softConfirmCount,
                "brk-1", 1, null, null);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal highestPrice,
            BigDecimal activeStop, BigDecimal mfeR, int softConfirmCount, String tranche2StopOrderId) {
        return openPosition(id, symbol, side, highestPrice, activeStop, mfeR, softConfirmCount,
                "brk-1", 1, null, tranche2StopOrderId);
    }

    /**
     * Full fixture. {@code brokerOrderId} and {@code stopOrderId} are deliberately DIFFERENT
     * ("brk-1" vs "stop-1") — that difference is what makes the bracket-id assertions a real
     * mutation probe rather than a tautology.
     */
    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal highestPrice,
            BigDecimal activeStop, BigDecimal mfeR, int softConfirmCount,
            String brokerOrderId, int tranche, String tranche2OrderId, String tranche2StopOrderId) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("90"), activeStop, tranche, null, List.of(), "sig-1", "agent", "2026-07-01",
                null, "OPEN", brokerOrderId, highestPrice, mfeR, softConfirmCount, null, null, null, null,
                "stop-1", null, null, tranche2OrderId, tranche2StopOrderId, 0, null, null,
                null, null, null, null);
    }

    @Test
    void usesBracketIdNotStopLegId() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        FakeExecutionGateway.ModifyCall call = gateway.modifyCalls.get(0);
        // Agora's modifyBracket resolves legs FROM the bracket id; handing it a leg id fails in
        // both lifecycle phases. The fixture sets brokerOrderId="brk-1" and stopOrderId="stop-1"
        // deliberately different, so this assertion is a real probe.
        assertThat(call.orderId()).isEqualTo("brk-1");
        assertThat(call.symbol()).isEqualTo("ACME");
        assertThat(call.stop()).isEqualByComparingTo("104");
        assertThat(call.target()).isNull();

        ArgumentCaptor<BigDecimal> newStopCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("110")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("1.0")),
                org.mockito.ArgumentMatchers.eq(0),
                newStopCaptor.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(newStopCaptor.getValue()).isEqualByComparingTo("104");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("MODIFY_STOP");
        assertThat(log.reasonCode()).isNull();
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.ruleVersion()).isEqualTo("exec-v0.2");
        assertThat(log.orderJson().get("new_stop").asDouble()).isEqualTo(104.0);
        assertThat(log.orderJson().get("stop_basis").asString()).contains("chandelier");

        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void neverLowersStop() {
        // chandelier = 110 - 3.0*5.33 ~= 94, below the existing active stop of 95 -> denied.
        ExecutorPosition p = openPosition(2L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("5.33")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void missingAtr_skips() {
        ExecutorPosition p = openPosition(3L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of(), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        // A symbol missing from the ATR map is a routine condition, not a fault: never escalate.
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void brokerUnavailable_escalates() {
        ExecutorPosition p = openPosition(4L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.unavailable = true;

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.symbol()).isEqualTo("ACME");

        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        // No "stop raised" push may go out when the stop did not move.
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2_nullLegId_stillEscalates() {
        // THE case that matters: on Saxo, tranche2StopOrderId is null BY DESIGN (the broker
        // returns no leg ids), so a gate keyed on that field would never fire. tranche is the
        // reliable marker.
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        assertThat(log.symbol()).isEqualTo("ACME");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2_withLegId_escalates() {
        ExecutorPosition p = openPosition(6L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", "s2");

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
    }

    @Test
    void tranche2OrderIdAlone_escalates() {
        // Belt-and-braces disjunct: tranche still 1, but a tranche-2 entry id is present.
        ExecutorPosition p = openPosition(7L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 1, "t2-1", null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
    }

    @Test
    void guardDenied_tranche2_writesNothing() {
        // Proves the gate sits AFTER guard.permit: chandelier 110 - 3*5.33 = 94.01 < stop 95,
        // so the guard denies first and no escalation row is written at all.
        ExecutorPosition p = openPosition(8L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("5.33")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void nullBrokerOrderId_escalatesNoBracketId() {
        ExecutorPosition p = openPosition(9L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, null, 1, null, null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("NO_BRACKET_ID");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        // No "stop raised" push may go out when the stop did not move.
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }
}
