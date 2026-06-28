package de.visterion.dracul.strigoi.echo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EchoDeterministicGateTest {

    // max-accrual 0.10, min-days-to-next 10
    private final EchoDeterministicGate gate = new EchoDeterministicGate(new BigDecimal("0.10"), 10);

    @Test
    void keepsCleanCandidate() {
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.03"), true), List.of(), 30);
        assertThat(d.skipped()).isFalse();
        assertThat(d.reason()).isNull();
    }

    @Test
    void skipsHighAccrual() {
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.25"), true), List.of(), 30);
        assertThat(d.skipped()).isTrue();
        assertThat(d.reason()).contains("accrual");
    }

    @Test
    void skipsOnConfounder() {
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.03"), true), List.of("m&a"), 30);
        assertThat(d.skipped()).isTrue();
        assertThat(d.reason()).contains("m&a");
    }

    @Test
    void skipsWhenNextEarningsTooClose() {
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.03"), true), List.of(), 5);
        assertThat(d.skipped()).isTrue();
        assertThat(d.reason()).contains("earnings");
    }

    @Test
    void confounderTakesPrecedenceOverAccrual() {
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.25"), true), List.of("restatement"), 5);
        assertThat(d.reason()).contains("restatement");
    }

    @Test
    void unavailableAccrualDoesNotSkip() {
        var d = gate.evaluate(AccrualMetrics.unavailable(), List.of(), 30);
        assertThat(d.skipped()).isFalse();
    }

    @Test
    void nullDaysToNextDoesNotSkipOnTiming() {
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.03"), true), List.of(), null);
        assertThat(d.skipped()).isFalse();
    }

    @Test
    void accrualRatioExactlyAtThresholdIsKept() {
        // ratio == max (0.10) must be KEPT (strict greater-than), guards a future >= regression
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.10"), true), List.of(), 30);
        assertThat(d.skipped()).isFalse();
    }

    @Test
    void accrualTakesPrecedenceOverTiming() {
        // high accrual AND imminent earnings -> reason is the accrual one (accrual checked before timing)
        var d = gate.evaluate(new AccrualMetrics(new BigDecimal("0.25"), true), List.of(), 5);
        assertThat(d.skipped()).isTrue();
        assertThat(d.reason()).contains("accrual");
    }
}
