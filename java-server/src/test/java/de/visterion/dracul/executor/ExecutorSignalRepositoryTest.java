package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorSignalRepositoryTest {

    @Autowired ExecutorSignalRepository repo;

    @Test
    void insertAndFindPending() {
        String id = UUID.randomUUID().toString();
        var s = new ExecutorSignal(id, "strigoi-spin", "v1", "PENDCO", "LONG", 0.82,
                "spin-off value unlock", List.of("EARNINGS_MISS", "GUIDANCE_CUT"), "6M",
                new java.math.BigDecimal("42.50"), "PENDING", null);
        repo.insert(s);

        var pending = repo.findPending(50);
        assertThat(pending).anySatisfy(r -> {
            assertThat(r.signalId()).isEqualTo(id);
            assertThat(r.symbol()).isEqualTo("PENDCO");
            assertThat(r.killCriteria()).containsExactlyInAnyOrder("EARNINGS_MISS", "GUIDANCE_CUT");
            assertThat(r.confidence()).isEqualTo(0.82);
        });
    }

    @Test
    void markStatusMovesOutOfPending() {
        String id = UUID.randomUUID().toString();
        var s = new ExecutorSignal(id, "strigoi-spin", "v1", "MARKCO", "LONG", 0.5,
                "mechanism", List.of(), "3M", null, "PENDING", null);
        repo.insert(s);

        repo.markStatus(id, "ACCEPTED");

        var pending = repo.findPending(50);
        assertThat(pending).noneSatisfy(r -> assertThat(r.symbol()).isEqualTo("MARKCO"));
        assertThat(repo.findById(id).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void nullConfidenceRoundTrips() {
        String id = UUID.randomUUID().toString();
        var s = new ExecutorSignal(id, "strigoi-spin", "v1", "NULLCO", "LONG", null,
                "mechanism", List.of(), "3M", null, "PENDING", null);
        repo.insert(s);

        var found = repo.findById(id);
        assertThat(found).isNotNull();
        assertThat(found.confidence()).isNull();
        assertThat(found.referencePrice()).isNull();
    }
}
