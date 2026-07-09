package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure soft-condition evaluator: chandelier breach and MA-cross, with a running confirm count. */
class SoftConditionEvaluatorTest {

    private final SoftConditionEvaluator evaluator = new SoftConditionEvaluator();

    @Test
    void chandelierBreach_incrementsCount() {
        SoftConditionEvaluator.SoftState state = evaluator.evaluate(
                new BigDecimal("100"), new BigDecimal("104"), null, null, 0);

        assertThat(state.chandelierBreach()).isTrue();
        assertThat(state.maBreak()).isFalse();
        assertThat(state.confirmCount()).isEqualTo(1);
    }

    @Test
    void maBreak_incrementsCount() {
        SoftConditionEvaluator.SoftState state = evaluator.evaluate(
                new BigDecimal("100"), null, new BigDecimal("50"), new BigDecimal("55"), 0);

        assertThat(state.chandelierBreach()).isFalse();
        assertThat(state.maBreak()).isTrue();
        assertThat(state.confirmCount()).isEqualTo(1);
    }

    @Test
    void neither_resetsToZero() {
        SoftConditionEvaluator.SoftState state = evaluator.evaluate(
                new BigDecimal("110"), new BigDecimal("104"), new BigDecimal("55"), new BigDecimal("50"), 3);

        assertThat(state.chandelierBreach()).isFalse();
        assertThat(state.maBreak()).isFalse();
        assertThat(state.confirmCount()).isEqualTo(0);
    }

    @Test
    void successiveBreaches_incrementCumulatively() {
        SoftConditionEvaluator.SoftState first = evaluator.evaluate(
                new BigDecimal("100"), new BigDecimal("104"), null, null, 0);
        SoftConditionEvaluator.SoftState second = evaluator.evaluate(
                new BigDecimal("99"), new BigDecimal("104"), null, null, first.confirmCount());
        SoftConditionEvaluator.SoftState third = evaluator.evaluate(
                new BigDecimal("98"), new BigDecimal("104"), null, null, second.confirmCount());

        assertThat(first.confirmCount()).isEqualTo(1);
        assertThat(second.confirmCount()).isEqualTo(2);
        assertThat(third.confirmCount()).isEqualTo(3);
    }

    @Test
    void nullInputs_flagsFalseAndCountResets() {
        SoftConditionEvaluator.SoftState state = evaluator.evaluate(null, null, null, null, 5);

        assertThat(state.chandelierBreach()).isFalse();
        assertThat(state.maBreak()).isFalse();
        assertThat(state.confirmCount()).isEqualTo(0);
    }
}
