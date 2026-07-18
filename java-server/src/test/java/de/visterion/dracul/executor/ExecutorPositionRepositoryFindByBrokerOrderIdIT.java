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
class ExecutorPositionRepositoryFindByBrokerOrderIdIT {

    @Autowired ExecutorPositionRepository repo;

    @Test
    void findsByBrokerOrderId() {
        repo.insert(new ExecutorPosition(null, "depot-1", "AAPL", "buy", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, new BigDecimal("1"),
                List.of("kc1"), "sig-xyz", "index-strigoi", null, null, "OPEN", "broker-abc",
                null, null, 0, null, null, null, null, null, null, null, null, null, 0, null, null, null, null, null, null));

        var found = repo.findByBrokerOrderId("broker-abc");

        assertThat(found).isNotNull();
        assertThat(found.symbol()).isEqualTo("AAPL");
        assertThat(found.sourceSignalId()).isEqualTo("sig-xyz");
        assertThat(repo.findByBrokerOrderId("does-not-exist")).isNull();
    }
}
