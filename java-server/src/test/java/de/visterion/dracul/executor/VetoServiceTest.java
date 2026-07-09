package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VetoServiceTest {

    private final VetoService vetoService = new VetoService();

    private ExecutorSignal signal(String symbol, Double confidence) {
        return signal(symbol, confidence, List.of("Close below 90.00"));
    }

    private ExecutorSignal signal(String symbol, Double confidence, List<String> killCriteria) {
        return new ExecutorSignal(
                "sig-1",
                "strigoi-test",
                "v1",
                symbol,
                "LONG",
                confidence,
                "mechanism",
                killCriteria,
                "horizon",
                BigDecimal.TEN,
                "PENDING",
                "2026-07-08T00:00:00Z");
    }

    @Test
    void passesWhenAllOk() {
        VetoService.Outcome outcome = vetoService.evaluate(signal("ACME", 0.8), 2, 0.6, 5);

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.firstFailure()).isNull();
        assertThat(outcome.results()).hasSize(3);
        assertThat(outcome.results()).allMatch(VetoResult::passed);
    }

    @Test
    void rejectsSchemaInvalid_nullSymbol() {
        VetoService.Outcome outcome = vetoService.evaluate(signal(null, 0.9), 0, 0.6, 5);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void rejectsSchemaInvalid_nullConfidence() {
        VetoService.Outcome outcome = vetoService.evaluate(signal("ACME", null), 0, 0.6, 5);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void rejectsLowConfidence() {
        VetoService.Outcome outcome = vetoService.evaluate(signal("ACME", 0.4), 0, 0.6, 5);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.LOW_CONFIDENCE);

        VetoResult schemaResult = outcome.results().stream()
                .filter(r -> r.check().equals("SCHEMA_INVALID"))
                .findFirst().orElseThrow();
        VetoResult confidenceResult = outcome.results().stream()
                .filter(r -> r.check().equals("LOW_CONFIDENCE"))
                .findFirst().orElseThrow();

        assertThat(schemaResult.passed()).isTrue();
        assertThat(confidenceResult.passed()).isFalse();
    }

    @Test
    void rejectsMaxPositions() {
        VetoService.Outcome outcome = vetoService.evaluate(signal("ACME", 0.9), 5, 0.6, 5);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.MAX_POSITIONS);
    }

    @Test
    void firstFailureOrderingSchemaBeatsCapacity() {
        VetoService.Outcome outcome = vetoService.evaluate(signal(null, 0.9), 10, 0.6, 5);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void confidenceExactlyAtThresholdPasses() {
        VetoService.Outcome outcome = vetoService.evaluate(signal("ACME", 0.6), 0, 0.6, 5);

        VetoResult confidenceResult = outcome.results().stream()
                .filter(r -> r.check().equals("LOW_CONFIDENCE"))
                .findFirst().orElseThrow();

        assertThat(confidenceResult.passed()).isTrue();
    }

    @Test
    void rejectsSchemaInvalid_emptyKillCriteria() {
        VetoService.Outcome outcome = vetoService.evaluate(signal("ACME", 0.9, List.of()), 0, 0.6, 5);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void rejectsSchemaInvalid_nullKillCriteria() {
        VetoService.Outcome outcome = vetoService.evaluate(signal("ACME", 0.9, null), 0, 0.6, 5);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }
}
