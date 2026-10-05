package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.strigoi.momentum.enabled=true")
class MomentumRepositoryTest {

    @Autowired MomentumRepository repo;
    @Autowired JdbcClient jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** A synthetic far-future year per test run keeps months unique in the reused container.
     *  Derived from a fresh UUID (not nanoTime, which can repeat across calls in the same
     *  test/JVM at this resolution) spread uniformly over the full disjoint range (ruling m7:
     *  3000–3999). */
    private static YearMonth month(int m) {
        int year = 3000 + (int) (Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1000L));
        return YearMonth.of(year, m);
    }

    @Test
    void aSnapshotIsInsertedOncePerRun() {
        String run = "mr-" + UUID.randomUUID();
        YearMonth m = month(10);

        assertThat(repo.insertSnapshot(run, LocalDate.parse("2026-10-30"), m, true, "healthy",
                mapper.readTree("{\"llm\": {\"a\": 1}}"))).isTrue();
        assertThat(repo.insertSnapshot(run, null, null, false, "not_due",
                mapper.readTree("{\"llm\": {\"a\": 2}}"))).isFalse();

        var s = repo.findSnapshot(run).orElseThrow();
        assertThat(s.month()).isEqualTo(m.toString());
        assertThat(s.rebalanceDue()).isTrue();
        assertThat(s.health()).isEqualTo("healthy");
        assertThat(s.asOfBarDate()).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(s.payload().path("llm").path("a").asInt()).isEqualTo(1);
        assertThat(repo.findSnapshot("mr-absent-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void lastDueSnapshotsAreOnePerMonthNewestFirstUpToTheGivenMonth() throws InterruptedException {
        YearMonth jan = month(1);
        YearMonth feb = jan.plusMonths(1), mar = jan.plusMonths(2), apr = jan.plusMonths(3);
        repo.insertSnapshot("mr-" + UUID.randomUUID(), null, jan, true, "healthy", mapper.readTree("{\"n\": \"jan\"}"));
        repo.insertSnapshot("mr-" + UUID.randomUUID(), null, feb, true, "unavailable", mapper.readTree("{\"n\": \"feb-u\"}"));
        repo.insertSnapshot("mr-" + UUID.randomUUID(), null, feb, true, "partial", mapper.readTree("{\"n\": \"feb\"}"));
        repo.insertSnapshot("mr-" + UUID.randomUUID(), null, mar, true, "healthy", mapper.readTree("{\"n\": \"mar-1\"}"));
        Thread.sleep(5);   // created_at decides between two snapshots of one month
        repo.insertSnapshot("mr-" + UUID.randomUUID(), null, mar, true, "healthy", mapper.readTree("{\"n\": \"mar-2\"}"));
        repo.insertSnapshot("mr-" + UUID.randomUUID(), null, apr, true, "healthy", mapper.readTree("{\"n\": \"apr\"}"));

        var last = repo.lastDueSnapshotPayloads(mar, 3);

        assertThat(last).extracting(n -> n.path("n").asString()).containsExactly("mar-2", "feb", "jan");
    }

    @Test
    void theMonthMarkIsIdempotent() {
        YearMonth m = month(7);
        // Belt-and-suspenders against a collision with an earlier test run on the reused
        // Testcontainers DB: the UUID-derived year already makes a collision unlikely, but a
        // clean slate for THIS exact month key is what the assertion below actually needs.
        jdbc.sql("DELETE FROM momentum_rebalance WHERE month = :m").param("m", m.toString()).update();
        assertThat(repo.rebalanceCompleted(m)).isFalse();
        assertThat(repo.markRebalanced(m, "run-a")).isTrue();
        assertThat(repo.markRebalanced(m, "run-b")).isFalse();
        assertThat(repo.rebalanceCompleted(m)).isTrue();
    }

    @Test
    void theStartMonthIsWrittenOnce() {
        YearMonth first = repo.startMonth(YearMonth.of(2026, 10));
        assertThat(repo.startMonth(YearMonth.of(2099, 1))).isEqualTo(first);
    }
}
