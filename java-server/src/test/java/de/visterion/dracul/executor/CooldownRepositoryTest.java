package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class CooldownRepositoryTest {

    @Autowired CooldownRepository repo;

    @Test
    void activeExcludesExpired() {
        Instant now = Instant.now();
        String activeSymbol = "ACME-" + UUID.randomUUID();
        String expiredSymbol = "ACME-" + UUID.randomUUID();

        repo.add(activeSymbol, "stop-out", now.plus(10, ChronoUnit.DAYS), "fresh setup only");
        repo.add(expiredSymbol, "stop-out", now.minus(1, ChronoUnit.DAYS), null);

        var active = repo.active(now);
        assertThat(active).anySatisfy(c -> {
            assertThat(c.symbol()).isEqualTo(activeSymbol);
            assertThat(c.reason()).isEqualTo("stop-out");
            assertThat(c.exceptionCondition()).isEqualTo("fresh setup only");
        });
        assertThat(active).noneSatisfy(c -> assertThat(c.symbol()).isEqualTo(expiredSymbol));
    }
}
