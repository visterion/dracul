package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository CRUD coverage for {@link ExecutorPositionLegRepository}. The V45 backfill itself
 * (the highest-risk part of this change) is covered separately by {@code
 * ExecutorPositionLegBackfillIT} and {@code ExecutorPositionLegBackfillGuardIT}, which run the
 * real migration via Flyway rather than a copy of its SQL.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionLegRepositoryTest {

    @Autowired ExecutorPositionLegRepository repo;
    @Autowired ExecutorPositionRepository positionRepo;

    @Test
    void insertAndFindOpenLegs() {
        long positionId = insertTestPosition("ACME-" + UUID.randomUUID(), new BigDecimal("20"));

        repo.insert(new ExecutorPositionLeg(null, positionId, 1, "ord-1", "stop-1",
                new BigDecimal("10"), ExecutorPositionLeg.OPEN, null, null, null));
        long leg2 = repo.insert(new ExecutorPositionLeg(null, positionId, 2, "ord-2", "stop-2",
                new BigDecimal("10"), ExecutorPositionLeg.OPEN, null, null, null));

        assertThat(repo.findOpenByPosition(positionId)).hasSize(2);

        repo.closeLeg(leg2, new BigDecimal("95"), "HARD_STOP",
                Instant.parse("2026-01-02T00:00:00Z"));

        List<ExecutorPositionLeg> open = repo.findOpenByPosition(positionId);
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().tranche()).isEqualTo(1);
        assertThat(repo.findByPosition(positionId)).hasSize(2);

        ExecutorPositionLeg closed = repo.findByPosition(positionId).stream()
                .filter(l -> l.id() == leg2).findFirst().orElseThrow();
        assertThat(closed.status()).isEqualTo(ExecutorPositionLeg.CLOSED);
        assertThat(closed.exitPrice()).isEqualByComparingTo("95");
        assertThat(closed.exitReason()).isEqualTo("HARD_STOP");
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.isOpen()).isFalse();
    }

    private long insertTestPosition(String symbol, BigDecimal qty) {
        var pos = new ExecutorPosition(null, "depot-1", symbol, "BUY",
                qty, new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-" + UUID.randomUUID(), "strigoi-spin",
                null, null, "OPEN", "ord-1",
                null, null, 0, null, null, null, null, "stop-1",
                "Technology", null, null, null, 0, null, null,
                null, null, null, null, false);
        return positionRepo.insert(pos);
    }
}
