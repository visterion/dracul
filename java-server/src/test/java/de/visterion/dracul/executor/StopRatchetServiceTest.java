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

    private StopRatchetService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new StopRatchetService(gateway, positionRepo, decisionRepo, ruleVersions,
                new StopRatchetGuard(), mapper, 3.0);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal highestPrice,
            BigDecimal activeStop, BigDecimal mfeR, int softConfirmCount) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("90"), activeStop, 1, null, List.of(), "sig-1", "agent", "2026-07-01",
                null, "OPEN", "brk-1", highestPrice, mfeR, softConfirmCount, null, null, null, null,
                "stop-1");
    }

    @Test
    void raisesStopToChandelier() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        FakeExecutionGateway.ModifyCall call = gateway.modifyCalls.get(0);
        assertThat(call.orderId()).isEqualTo("stop-1");
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
    }
}
