package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
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
                VALUES (:id, 'ACME', 'Acme Corp', 'PEAD', 0.7, 'thesis',
                        '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 'SWING', 'oracle', now(),
                        'default', 'run-xyz')
                """)
                .param("id", preyId)
                .update();
        var s = new ExecutorSignal("sig-run-1", "strigoi", "v1", "ACME", "LONG", 0.7,
                "PEAD", java.util.List.of(), "SWING", null, "PENDING", null, null, preyId.toString());
        repo.insert(s);

        assertThat(repo.findRunIdBySignalId("sig-run-1")).isEqualTo("run-xyz");
    }

    @Test
    void findRunIdBySignalIdNullWhenNoPreyLink() {
        var s = new ExecutorSignal("sig-run-2", "strigoi", "v1", "ACME", "LONG", 0.7,
                "PEAD", java.util.List.of(), "SWING", null, "PENDING", null);
        repo.insert(s);

        assertThat(repo.findRunIdBySignalId("sig-run-2")).isNull();
        assertThat(repo.findRunIdBySignalId("does-not-exist")).isNull();
    }

    @Test
    void referenceBarDateAndAtrRoundTrip() {
        String id = UUID.randomUUID().toString();
        repo.insert(new ExecutorSignal(id, "strigoi-spin", "v1", "SKIPCO", "BUY", 0.7,
                "SPINOFF", List.of(), "3m", new java.math.BigDecimal("101.5"), "PENDING", null,
                null, null, java.time.LocalDate.parse("2026-09-04"), new java.math.BigDecimal("3.100000"), null));

        var found = repo.findById(id);
        // Read via getObject(LocalDate.class), never through a Timestamp -- a Timestamp read
        // would shift the day in a non-UTC JVM.
        assertThat(found.referenceBarDate()).isEqualTo(java.time.LocalDate.parse("2026-09-04"));
        assertThat(found.referenceAtr()).isEqualByComparingTo("3.1");
    }

    @Test
    void signalsWithoutReferenceInputsRoundTripAsNull() {
        String id = UUID.randomUUID().toString();
        repo.insert(new ExecutorSignal(id, "strigoi-spin", "v1", "NOREF", "BUY", 0.7,
                "SPINOFF", List.of(), "3m", null, "PENDING", null));

        var found = repo.findById(id);
        assertThat(found.referenceBarDate()).isNull();
        assertThat(found.referenceAtr()).isNull();
    }

    /** V51: the structured kill level round-trips; a signal without one reads back null. */
    @Test
    void killCloseBelowRoundTrips() {
        String id = UUID.randomUUID().toString();
        repo.insert(new ExecutorSignal(id, "strigoi-echo", "v1", "KLSIG", "BUY", 0.7,
                "PEAD", List.of(), "3m", null, "PENDING", null,
                null, null, null, null, new java.math.BigDecimal("48.2")));
        String none = UUID.randomUUID().toString();
        repo.insert(new ExecutorSignal(none, "strigoi-echo", "v1", "KLSIGN", "BUY", 0.7,
                "PEAD", List.of(), "3m", null, "PENDING", null));

        assertThat(repo.findById(id).killCloseBelow()).isEqualByComparingTo("48.2");
        assertThat(repo.findById(none).killCloseBelow()).isNull();
        assertThat(repo.findPending(Integer.MAX_VALUE)).filteredOn(s -> s.signalId().equals(id))
                .singleElement()
                .satisfies(s -> assertThat(s.killCloseBelow()).isEqualByComparingTo("48.2"));
    }

    @Test
    void countByMechanismAndStatusSinceCountsOnlyThatStatusInTheWindow() {
        String accepted = UUID.randomUUID().toString();
        String rejected = UUID.randomUUID().toString();
        String symbol = "TCNT" + System.nanoTime();
        Instant before = Instant.now().minusSeconds(5);
        repo.insert(new ExecutorSignal(accepted, "strigoi-tech", "v1", symbol, "BUY", 0.8,
                " tech_conviction ", List.of("x"), "12m", null, "PENDING", null));
        repo.insert(new ExecutorSignal(rejected, "strigoi-tech", "v1", symbol + "R", "BUY", 0.8,
                "TECH_CONVICTION", List.of("x"), "12m", null, "PENDING", null));
        int baseline = repo.countByMechanismAndStatusSince("TECH_CONVICTION", "ACCEPTED", before);
        repo.markStatus(accepted, "ACCEPTED");
        repo.markStatus(rejected, "REJECTED");

        assertThat(repo.countByMechanismAndStatusSince("TECH_CONVICTION", "ACCEPTED", before))
                .isEqualTo(baseline + 1);
        assertThat(repo.countByMechanismAndStatusSince("TECH_CONVICTION", "ACCEPTED",
                Instant.now().plusSeconds(60))).isZero();
    }
}
