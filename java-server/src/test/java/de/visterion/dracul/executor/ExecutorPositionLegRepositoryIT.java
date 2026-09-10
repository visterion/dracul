package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code existsByStopOrderId} is half of the SP4 symbol-bound stop binding's "is this leg
 *  already spoken for?" check — the other half is
 *  {@code ExecutorPositionRepository.stopOrderIdClaimed}. Legs outlive their columns, so a leg
 *  row alone can be the only claim on an order. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionLegRepositoryIT {

    @Autowired ExecutorPositionLegRepository legRepo;
    @Autowired ExecutorPositionRepository positionRepo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM executor_position_leg").update();
        jdbc.sql("DELETE FROM executor_position").update();
    }

    private long position(String symbol) {
        return positionRepo.insert(new ExecutorPosition(null, "depot-1", symbol, "buy",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 1, new BigDecimal("1"), List.of("kc1"), "sig-1",
                "index-strigoi", null, null, "OPEN", "ord-entry", new BigDecimal("100"), null, 0,
                null, null, null, null, null, null, null, null, null, 0, null, null, null, null,
                null, null, false, null, null));
    }

    @Test
    void existsByStopOrderId_seesOpenAndClosedLegsAndNothingElse() {
        long openPosition = position("LEGCO");
        long closedPosition = position("LEGCO2");
        legRepo.insert(new ExecutorPositionLeg(null, openPosition, 1, "ord-entry-1", "ord-stop-1",
                new BigDecimal("10"), ExecutorPositionLeg.OPEN, null, null, null));
        legRepo.insert(new ExecutorPositionLeg(null, closedPosition, 1, "ord-entry-2", "ord-stop-2",
                new BigDecimal("10"), "CLOSED", new BigDecimal("90"), "HARD_STOP", null));

        assertThat(legRepo.existsByStopOrderId("ord-stop-1")).isTrue();
        assertThat(legRepo.existsByStopOrderId("ord-stop-2")).isTrue();
        assertThat(legRepo.existsByStopOrderId("ord-unclaimed")).isFalse();
        assertThat(legRepo.existsByStopOrderId(null)).isFalse();
    }
}
