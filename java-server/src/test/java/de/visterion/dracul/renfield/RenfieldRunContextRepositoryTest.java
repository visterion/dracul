package de.visterion.dracul.renfield;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class RenfieldRunContextRepositoryTest {

    @Autowired RenfieldRunContextRepository repo;

    @Test
    void saveThenFindBySymbolRoundTrips() {
        String runId = "run-" + System.nanoTime();
        repo.save(runId, Map.of("ACME", true, "OTHR", false), "depot");

        var held = repo.findBySymbol(runId, "ACME").orElseThrow();
        assertThat(held.held()).isTrue();
        assertThat(held.positionSource()).isEqualTo("depot");
        assertThat(held.createdAt()).isNotNull();

        var notHeld = repo.findBySymbol(runId, "OTHR").orElseThrow();
        assertThat(notHeld.held()).isFalse();
    }

    @Test
    void findBySymbolEmptyWhenUnknown() {
        assertThat(repo.findBySymbol("run-does-not-exist", "ACME")).isEmpty();
    }

    @Test
    void saveOverwritesExistingSnapshotForSameRunAndSymbol() {
        String runId = "run-" + System.nanoTime();
        repo.save(runId, Map.of("ACME", true), "depot");
        repo.save(runId, Map.of("ACME", false), "fallback");

        var row = repo.findBySymbol(runId, "ACME").orElseThrow();
        assertThat(row.held()).isFalse();
        assertThat(row.positionSource()).isEqualTo("fallback");
    }

    @Test
    void saveEmptyOrNullMapIsNoop() {
        String runId = "run-" + System.nanoTime();
        repo.save(runId, Map.of(), "depot");
        repo.save(runId, null, "depot");

        assertThat(repo.findBySymbol(runId, "ANY")).isEmpty();
    }
}
