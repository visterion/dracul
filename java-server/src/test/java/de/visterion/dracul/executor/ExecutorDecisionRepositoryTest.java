package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorDecisionRepositoryTest {

    @Autowired ExecutorDecisionRepository repo;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void insertAcceptedAndRejected() {
        String symbolAccepted = "DEC-ACC-" + UUID.randomUUID();
        String symbolRejected = "DEC-REJ-" + UUID.randomUUID();
        String orderId = "ord-" + UUID.randomUUID();

        var accepted = new ExecutorDecision(null, "sig-accepted", symbolAccepted, true,
                null, List.of(), "entered", orderId, "run-1", null);
        var rejected = new ExecutorDecision(null, "sig-rejected", symbolRejected, false,
                "LOW_CONFIDENCE", List.of("SCHEMA_INVALID", "LOW_CONFIDENCE"), null, null, "run-1", null);

        long idAccepted = repo.insert(accepted);
        long idRejected = repo.insert(rejected);

        assertThat(idAccepted).isPositive();
        assertThat(idRejected).isPositive();
        assertThat(idAccepted).isNotEqualTo(idRejected);

        var recent = repo.findRecent(50);
        assertThat(recent).extracting(ExecutorDecision::symbol).contains(symbolAccepted, symbolRejected);

        var foundAccepted = recent.stream().filter(d -> d.symbol().equals(symbolAccepted)).findFirst().orElseThrow();
        assertThat(foundAccepted.accepted()).isTrue();
        assertThat(foundAccepted.brokerOrderId()).isEqualTo(orderId);

        var foundRejected = recent.stream().filter(d -> d.symbol().equals(symbolRejected)).findFirst().orElseThrow();
        assertThat(foundRejected.accepted()).isFalse();
        assertThat(foundRejected.rejectReason()).isEqualTo("LOW_CONFIDENCE");
        assertThat(foundRejected.vetoTrace()).containsExactlyInAnyOrder("SCHEMA_INVALID", "LOW_CONFIDENCE");
    }

    @Test
    void countByReason() {
        String signalId = "sig-broker-" + UUID.randomUUID();
        String otherSignalId = "sig-broker-other-" + UUID.randomUUID();

        var brokerError1 = new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "broker call failed", null, "run-1", null);
        var brokerError2 = new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "broker call failed", null, "run-1", null);
        var vetoReject = new ExecutorDecision(null, signalId, "ACME", false,
                "LOW_CONFIDENCE", List.of("LOW_CONFIDENCE"), null, null, "run-1", null);

        repo.insert(brokerError1);
        repo.insert(brokerError2);
        repo.insert(vetoReject);

        assertThat(repo.countByReason(signalId, "BROKER_ERROR")).isEqualTo(2);
        assertThat(repo.countByReason(signalId, "LOW_CONFIDENCE")).isEqualTo(1);
        assertThat(repo.countByReason(otherSignalId, "BROKER_ERROR")).isEqualTo(0);
    }

    @Test
    void countByReasonInRun_onlyCountsRowsOfThatRun() {
        String signalId = "sig-inrun-" + UUID.randomUUID();

        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "429", null, "run-A", null));
        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "duplicate", null, "run-A", null));
        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "429", null, "run-B", null));
        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "LOW_CONFIDENCE", List.of(), null, null, "run-A", null));

        assertThat(repo.countByReasonInRun(signalId, "BROKER_ERROR", "run-A")).isEqualTo(2);
        assertThat(repo.countByReasonInRun(signalId, "BROKER_ERROR", "run-B")).isEqualTo(1);
        assertThat(repo.countByReasonInRun(signalId, "BROKER_ERROR", "run-C")).isZero();
    }

    @Test
    void countDistinctRunsByReasonSince_countsRunsNotRows() {
        // This is THE regression that locked STT out on 2026-07-22: three broker errors in a
        // single run are ONE failed attempt, not three. Counting rows let one night exhaust a
        // lifetime cap of 3.
        String signalId = "sig-runs-" + UUID.randomUUID();
        Instant since = Instant.now().minus(Duration.ofHours(72));

        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "429", null, "run-A", null));
        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "duplicate", null, "run-A", null));
        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "429", null, "run-A", null));

        assertThat(repo.countDistinctRunsByReasonSince(signalId, "BROKER_ERROR", since))
                .isEqualTo(1);
    }

    @Test
    void countDistinctRunsByReasonSince_excludesRowsOutsideTheWindow() {
        String signalId = "sig-window-" + UUID.randomUUID();

        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "old", null, "run-old", null));
        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "fresh", null, "run-fresh", null));

        // Backdate the "old" run well beyond a 72h window.
        jdbc.sql("UPDATE executor_decision SET created_at = now() - interval '5 days' "
                        + "WHERE signal_id = :signalId AND run_id = 'run-old'")
                .param("signalId", signalId)
                .update();

        Instant since = Instant.now().minus(Duration.ofHours(72));

        assertThat(repo.countDistinctRunsByReasonSince(signalId, "BROKER_ERROR", since))
                .as("only the fresh run is inside the window")
                .isEqualTo(1);
        assertThat(repo.countDistinctRunsByReasonSince(signalId, "BROKER_ERROR",
                Instant.now().minus(Duration.ofDays(30))))
                .as("a wide enough window sees both runs")
                .isEqualTo(2);
    }

    @Test
    void countDistinctRunsByReasonSince_ignoresRowsWithoutARun() {
        // A row without a run_id has no attempt axis and must not represent an attempt.
        String signalId = "sig-norun-" + UUID.randomUUID();
        Instant since = Instant.now().minus(Duration.ofHours(72));

        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "no run", null, null, null));

        assertThat(repo.countDistinctRunsByReasonSince(signalId, "BROKER_ERROR", since)).isZero();
    }

    @Test
    void countDistinctRunsByReasonSince_isScopedToTheSignal() {
        String signalId = "sig-scope-" + UUID.randomUUID();
        String otherSignalId = "sig-scope-other-" + UUID.randomUUID();
        Instant since = Instant.now().minus(Duration.ofHours(72));

        repo.insert(new ExecutorDecision(null, signalId, "ACME", false,
                "BROKER_ERROR", List.of(), "429", null, "run-A", null));
        repo.insert(new ExecutorDecision(null, otherSignalId, "ACME", false,
                "BROKER_ERROR", List.of(), "429", null, "run-B", null));

        assertThat(repo.countDistinctRunsByReasonSince(signalId, "BROKER_ERROR", since))
                .isEqualTo(1);
    }
}
