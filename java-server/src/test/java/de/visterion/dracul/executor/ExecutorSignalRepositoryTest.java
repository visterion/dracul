package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    @Autowired JdbcClient jdbc;

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

    @Test
    void preyIdRoundTrips() {
        String id = UUID.randomUUID().toString();
        String preyId = UUID.randomUUID().toString();
        var s = new ExecutorSignal(id, "strigoi-spin", "v1", "PREYCO", "LONG", 0.6,
                "mechanism", List.of(), "3M", null, "PENDING", null, null, preyId);
        repo.insert(s);

        var found = repo.findById(id);
        assertThat(found).isNotNull();
        assertThat(found.preyId()).isEqualTo(preyId);
    }

    @Test
    void injectStyleSignalHasNullPreyId() {
        String id = UUID.randomUUID().toString();
        var s = new ExecutorSignal(id, "injected", "operator", "INJCO", "LONG", 0.6,
                "mechanism", List.of(), "3M", null, "PENDING", null);
        repo.insert(s);

        var found = repo.findById(id);
        assertThat(found).isNotNull();
        assertThat(found.preyId()).isNull();
    }

    @Test
    void findRunIdBySignalIdReturnsPreyRunId() {
        var preyId = java.util.UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO prey (id, symbol, company_name, anomaly_type, confidence, thesis,
                                  signals, risks, kill_criteria, horizon, discovered_by, discovered_at,
                                  user_id, run_id)
                VALUES (:id, 'AAPL', 'Apple', 'PEAD', 0.7, 'thesis',
                        '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 'SWING', 'oracle', now(),
                        'default', 'run-xyz')
                """)
                .param("id", preyId)
                .update();
        var s = new ExecutorSignal("sig-run-1", "strigoi", "v1", "AAPL", "LONG", 0.7,
                "PEAD", java.util.List.of(), "SWING", null, "PENDING", null, null, preyId.toString());
        repo.insert(s);

        assertThat(repo.findRunIdBySignalId("sig-run-1")).isEqualTo("run-xyz");
    }

    @Test
    void findRunIdBySignalIdNullWhenNoPreyLink() {
        var s = new ExecutorSignal("sig-run-2", "strigoi", "v1", "AAPL", "LONG", 0.7,
                "PEAD", java.util.List.of(), "SWING", null, "PENDING", null);
        repo.insert(s);

        assertThat(repo.findRunIdBySignalId("sig-run-2")).isNull();
        assertThat(repo.findRunIdBySignalId("does-not-exist")).isNull();
    }
}
