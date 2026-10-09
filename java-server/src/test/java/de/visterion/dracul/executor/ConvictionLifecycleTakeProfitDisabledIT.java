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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 2026-10-09: the take-profit-enabled property is absent here, so the real bound
 * {@link ConvictionProfile} carries its default ({@code false}) through the full
 * ReconcileService/HardTriggerService wiring over Postgres and a {@link FakeExecutionGateway} —
 * proving the default-off path end to end, not just at the unit level. A +40 % close must not
 * half-sell; the position survives untouched.
 */
@SpringBootTest
@Import({ContainerConfig.class, ConvictionLifecycleTakeProfitDisabledIT.FakeGatewayConfig.class})
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.broker-stop-buffer-atr=0"})
class ConvictionLifecycleTakeProfitDisabledIT {

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
    @Autowired ConvictionProfile convictionProfile;

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
    void defaultProfileHasTakeProfitDisabled() {
        assertThat(convictionProfile.takeProfitEnabled()).isFalse();
    }

    @Test
    void fortyPercentCloseProducesNoTargetHalfWithTheDefaultProfile() {
        String connection = "lifecycle-off-" + System.nanoTime();
        String symbol = "SYOF";
        long id = positions.insert(ExecutorPositionFixtures.withProfileFields(
                ExecutorPositionFixtures.withoutKillLevel(null, connection, symbol, "BUY",
                        new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65.00"),
                        new BigDecimal("65.00"), 1, null, List.of("X"), "sig-" + connection,
                        "strigoi-tech", null, null, "OPEN", "brk-" + connection,
                        new BigDecimal("100"), null, 0, null, null, null, null, null, null, null,
                        null, null, 0, null, null, null, null, null, null, false,
                        new BigDecimal("80.00"), "2026-07-01T00:00:00Z"),
                ExitProfile.CONVICTION, null, null, null, true));

        gateway.seedPosition(new BrokerPosition(symbol, "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("140"), 0));
        List<ExecutorPosition> afterHard = hardTrigger.apply(reconciled(connection, id, "run-off-1"),
                Map.of(symbol, new BigDecimal("140")), "run-off-1");

        assertThat(gateway.flattenFractions).isEmpty();
        assertThat(afterHard).singleElement()
                .satisfies(p -> assertThat(p.trimCount()).isZero());
        ExecutorPosition unchanged = positions.findById(id);
        assertThat(unchanged.trimCount()).isZero();
        assertThat(unchanged.qty()).isEqualByComparingTo("10");
        assertThat(unchanged.status()).isEqualTo("OPEN");
    }
}
