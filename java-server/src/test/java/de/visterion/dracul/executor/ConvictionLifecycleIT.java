package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 2026-10-03 §9: one CONVICTION position through real ReconcileService, HardTriggerService,
 * PartialExitService and StopRatchetService over Postgres and a {@link FakeExecutionGateway}:
 * target-half at +30 %, exactly once (also on a second pass of the same run), trail at 70 % of
 * the highest close afterwards, emergency/trail stop at the end. A unique connection keeps the
 * shared container's other OPEN rows out of reconcile.
 */
@SpringBootTest
@Import({ContainerConfig.class, ConvictionLifecycleIT.FakeGatewayConfig.class})
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.broker-stop-buffer-atr=0"})
class ConvictionLifecycleIT {

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

    private List<ExecutorPosition> reconciled(String connection, long id, String runId) {
        return reconcile.reconcile(connection, runId).survivors().stream()
                .filter(p -> p.id() == id).toList();
    }

    @Test
    void targetHalfOnceThenTrailThenStop() {
        String connection = "lifecycle-" + System.nanoTime();
        String symbol = "SYNL";
        long id = positions.insert(ExecutorPositionFixtures.conviction(
                ExecutorPositionFixtures.withoutKillLevel(null, connection, symbol, "BUY",
                        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65.00"),
                        new BigDecimal("65.00"), 1, null, List.of("X"), "sig-" + connection,
                        "strigoi-tech", null, null, "OPEN", "brk-" + connection,
                        new BigDecimal("100"), null, 0, null, null, null, null, null, null, null,
                        null, null, 0, null, null, null, null, null, null, false,
                        new BigDecimal("65.00"), "2026-07-01T00:00:00Z")));

        // Night 1: close 131 -> target-half (0.5 of 10)
        gateway.seedPosition(new BrokerPosition(symbol, "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("131"), 0));
        hardTrigger.apply(reconciled(connection, id, "run-1"),
                Map.of(symbol, new BigDecimal("131")), "run-1");
        assertThat(gateway.flattenFractions).containsExactly(new BigDecimal("0.5"));
        ExecutorPosition halfSold = positions.findById(id);
        assertThat(halfSold.trimCount()).isEqualTo(1);
        assertThat(halfSold.qty()).isEqualByComparingTo("5");
        assertThat(halfSold.status()).isEqualTo("OPEN");

        // Second maintenance pass of the same run: the re-read row blocks a second half-sale
        hardTrigger.apply(List.of(positions.findById(id)), Map.of(symbol, new BigDecimal("131")), "run-1");
        assertThat(gateway.flattenFractions).hasSize(1);

        // Night 2: highest close 140 -> trail 98.00
        gateway.seedPosition(new BrokerPosition(symbol, "BUY", new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("140"), 0));
        List<ExecutorPosition> night2 = hardTrigger.apply(reconciled(connection, id, "run-2"),
                Map.of(symbol, new BigDecimal("140")), "run-2");
        ratchet.ratchet(night2, Map.of(symbol, new BigDecimal("2")), Map.of(),
                Map.of(symbol, new BigDecimal("2")), Map.of(symbol, new BigDecimal("140")), "run-2");
        assertThat(positions.findById(id).activeStop()).isEqualByComparingTo("98.00");

        // Night 3: close 97 -> HARD_STOP on the trail
        gateway.seedPosition(new BrokerPosition(symbol, "BUY", new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("97"), 0));
        hardTrigger.apply(reconciled(connection, id, "run-3"),
                Map.of(symbol, new BigDecimal("97")), "run-3");
        assertThat(gateway.flattenFractions).last().isEqualTo(BigDecimal.ONE);
        assertThat(positions.findById(id).pendingExitReason()).isEqualTo("HARD_STOP");
    }
}
