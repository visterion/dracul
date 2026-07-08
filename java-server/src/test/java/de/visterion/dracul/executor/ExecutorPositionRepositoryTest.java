package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionRepositoryTest {

    @Autowired ExecutorPositionRepository repo;

    @Test
    void insertReturnsIdAndFindsOpen() {
        String symbolA = "POS-A-" + UUID.randomUUID();
        String symbolB = "POS-B-" + UUID.randomUUID();

        var posA = new ExecutorPosition(null, "saxo-sim", symbolA, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS", "GUIDANCE_CUT"), "sig-a", "strigoi-spin",
                null, null, "OPEN", null);
        var posB = new ExecutorPosition(null, "saxo-sim", symbolB, "BUY",
                new BigDecimal("5"), new BigDecimal("50.00"), new BigDecimal("45.00"),
                new BigDecimal("47.00"), 1, new BigDecimal("0.8"),
                List.of("STOP_HIT"), "sig-b", "strigoi-insider",
                null, null, "OPEN", null);

        long idA = repo.insert(posA);
        long idB = repo.insert(posB);

        assertThat(idA).isPositive();
        assertThat(idB).isPositive();
        assertThat(idA).isNotEqualTo(idB);

        assertThat(repo.countOpen()).isGreaterThanOrEqualTo(2);

        var open = repo.findOpen();
        assertThat(open).extracting(ExecutorPosition::symbol).contains(symbolA, symbolB);

        var found = open.stream().filter(p -> p.symbol().equals(symbolA)).findFirst().orElseThrow();
        assertThat(found.killCriteria()).containsExactlyInAnyOrder("EARNINGS_MISS", "GUIDANCE_CUT");
        assertThat(found.entryPrice()).isEqualByComparingTo("100.00");
    }
}
