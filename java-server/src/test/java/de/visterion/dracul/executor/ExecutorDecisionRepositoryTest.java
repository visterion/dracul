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
class ExecutorDecisionRepositoryTest {

    @Autowired ExecutorDecisionRepository repo;

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
}
