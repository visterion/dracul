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
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

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

    /** The 30-day sweep the scheduler runs on every trigger: rows past the window go,
     *  today's snapshot stays. */
    @Test
    void deleteOlderThanDropsAgedRowsAndKeepsFreshOnes() {
        String oldRun = "run-old-" + System.nanoTime();
        String freshRun = "run-fresh-" + System.nanoTime();
        repo.save(oldRun, Map.of("ACME", true), "ok");
        repo.save(freshRun, Map.of("ACME", true), "ok");
        jdbc.sql("UPDATE renfield_run_context SET created_at = now() - interval '31 days' "
                        + "WHERE run_id = :runId")
                .param("runId", oldRun)
                .update();

        int purged = repo.deleteOlderThan(30);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(repo.findBySymbol(oldRun, "ACME")).isEmpty();
        assertThat(repo.findBySymbol(freshRun, "ACME")).isPresent();
    }

    @Test
    void saveEmptyOrNullMapIsNoop() {
        String runId = "run-" + System.nanoTime();
        repo.save(runId, Map.of(), "depot");
        repo.save(runId, null, "depot");

        assertThat(repo.findBySymbol(runId, "ANY")).isEmpty();
    }
}
