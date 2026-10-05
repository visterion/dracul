package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 2026-10-04 §9: one MOMENTUM position through real ReconcileService, HardTriggerService and
 * StopRatchetService over Postgres and a {@link FakeExecutionGateway}: post-fill widening of the
 * narrow entry leg, no chandelier ratchet and no target-half however far it runs, then a
 * rebalance flag → HARD_REBALANCE flatten → reconcile closes it with exit_reason HARD_REBALANCE.
 */
@SpringBootTest
@Import({ContainerConfig.class, MomentumLifecycleIT.FakeGatewayConfig.class})
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.broker-stop-buffer-atr=0"})
class MomentumLifecycleIT {

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        FakeExecutionGateway fakeExecutionGateway() {
            return new FakeExecutionGateway();
        }
    }

    @Autowired FakeExecutionGateway gateway;
    @Autowired ExecutorPositionRepository positions;
    @Autowired ReconcileService reconcile;
    @Autowired HardTriggerService hardTrigger;
    @Autowired StopRatchetService ratchet;

    @BeforeEach
    void resetGatewayRecordings() {
        gateway.modifyCalls.clear();
        gateway.flattenFractions.clear();
    }

    private List<ExecutorPosition> reconciled(String connection, long id, String runId) {
        return reconcile.reconcile(connection, runId).survivors().stream()
                .filter(p -> p.id() == id).toList();
    }

    @Test
    void wideningNoRatchetThenRebalanceExit() {
        String connection = "mlife-" + System.nanoTime();
        String symbol = "SYNV";
        long id = positions.insert(ExecutorPositionFixtures.withProfileFields(
                ExecutorPositionFixtures.withoutKillLevel(null, connection, symbol, "BUY",
                        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65.00"),
                        new BigDecimal("65.00"), 1, null, List.of("X"), "sig-" + connection,
                        "strigoi-momentum", null, null, "OPEN", "brk-" + connection,
                        new BigDecimal("100"), null, 0, null, null, null, null, null, null, null,
                        null, null, 0, null, null, null, null, null, null, false,
                        new BigDecimal("80.00"), "2026-07-01T00:00:00Z"),
                ExitProfile.MOMENTUM, null, null, null, true));

        // Night 0: the narrow −20 % leg is widened to the logical stop (65).
        gateway.seedPosition(new BrokerPosition(symbol, "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("105"), 0));
        List<ExecutorPosition> night0 = hardTrigger.apply(reconciled(connection, id, "run-0"),
                Map.of(symbol, new BigDecimal("105")), "run-0");
        ratchet.ratchet(night0, Map.of(symbol, new BigDecimal("2")), Map.of(),
                Map.of(symbol, new BigDecimal("2")), Map.of(symbol, new BigDecimal("105")), "run-0");
        assertThat(gateway.modifyCalls).singleElement()
                .satisfies(c -> assertThat(c.stop()).isEqualByComparingTo("65.00"));
        assertThat(positions.findById(id).brokerStopNarrow()).isFalse();
        gateway.modifyCalls.clear();

        // Night 1: +100 % — no target-half, no chandelier ratchet (STANDARD would move to 194).
        gateway.seedPosition(new BrokerPosition(symbol, "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("200"), 0));
        List<ExecutorPosition> night1 = hardTrigger.apply(reconciled(connection, id, "run-1"),
                Map.of(symbol, new BigDecimal("200")), "run-1");
        ratchet.ratchet(night1, Map.of(symbol, new BigDecimal("2")), Map.of(),
                Map.of(symbol, new BigDecimal("2")), Map.of(symbol, new BigDecimal("200")), "run-1");
        assertThat(gateway.flattenFractions).isEmpty();
        assertThat(gateway.modifyCalls).isEmpty();
        assertThat(positions.findById(id).activeStop()).isEqualByComparingTo("65.00");

        // The momentum completion commits the name to a rebalance exit.
        assertThat(positions.markRebalanceExit(id, connection, Instant.now())).isTrue();

        // Night 2: full flatten with HARD_REBALANCE.
        hardTrigger.apply(reconciled(connection, id, "run-2"), Map.of(symbol, new BigDecimal("190")), "run-2");
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);
        assertThat(positions.findById(id).pendingExitReason()).isEqualTo("HARD_REBALANCE");

        // Night 3: the broker no longer holds it -> reconcile closes the book row.
        reconcile.reconcile(connection, "run-3");
        ExecutorPosition closed = positions.findById(id);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.exitReason()).isEqualTo("HARD_REBALANCE");
    }
}
