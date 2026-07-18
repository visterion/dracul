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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionRepositoryFindBySourceSignalIdIT {

    @Autowired ExecutorPositionRepository repo;

    @Test
    void findsMostRecentBySourceSignalId() throws InterruptedException {
        repo.insert(new ExecutorPosition(null, "depot-sigtest", "SIGA", "buy", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, new BigDecimal("1"),
                List.of("kc1"), "sig-1", "index-strigoi", null, null, "OPEN", "broker-older",
                null, null, 0, null, null, null, null, null, null, null, null, null, 0, null, null, null, null, null, null));

        // Ensure a distinguishable entry_date ordering (timestamptz default now()).
        Thread.sleep(20);

        repo.insert(new ExecutorPosition(null, "depot-sigtest", "SIGB", "buy", new BigDecimal("5"),
                new BigDecimal("200"), new BigDecimal("190"), new BigDecimal("190"), 1, new BigDecimal("1"),
                List.of("kc2"), "sig-1", "index-strigoi", null, null, "OPEN", "broker-newer",
                null, null, 0, null, null, null, null, null, null, null, null, null, 0, null, null, null, null, null, null));

        var found = repo.findBySourceSignalId("sig-1");

        assertThat(found).isNotNull();
        assertThat(found.sourceSignalId()).isEqualTo("sig-1");
        assertThat(found.symbol()).isEqualTo("SIGB");
        assertThat(repo.findBySourceSignalId("nope")).isNull();
    }
}
