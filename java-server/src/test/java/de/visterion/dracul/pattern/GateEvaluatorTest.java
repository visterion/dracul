package de.visterion.dracul.pattern;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GateEvaluatorTest {

    private static final GateSignalView FULL = new GateSignalView(
            "INSIDER_CLUSTER", "ACME", "Biotechnology", 0.8, new BigDecimal("4.50"));

    private GatePredicate gate(GateCondition... cs) {
        return new GatePredicate(List.of(cs));
    }

    private GateCondition str(String field, String op, String... values) {
        return new GateCondition(field, op, List.of(values), null);
    }

    private GateCondition num(String field, String op, String value) {
        return new GateCondition(field, op, null, new BigDecimal(value));
    }

    // ---- match / no-match per op ----

    @Test
    void eqMatchesAndMismatches() {
        assertThat(GateEvaluator.evaluate(gate(str("mechanism", "eq", "INSIDER_CLUSTER")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(str("mechanism", "eq", "PEAD")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
    }

    @Test
    void neMatchesAndMismatches() {
        assertThat(GateEvaluator.evaluate(gate(str("mechanism", "ne", "PEAD")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(str("mechanism", "ne", "INSIDER_CLUSTER")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
    }

    @Test
    void inAndNotIn() {
        assertThat(GateEvaluator.evaluate(gate(str("symbol", "in", "ACME", "FOO")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(str("symbol", "in", "FOO")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
        assertThat(GateEvaluator.evaluate(gate(str("symbol", "not_in", "FOO")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(str("symbol", "not_in", "ACME")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
    }

    @Test
    void numericOps() {
        assertThat(GateEvaluator.evaluate(gate(num("price", "lt", "5")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(num("price", "gt", "5")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
        assertThat(GateEvaluator.evaluate(gate(num("confidence", "gte", "0.8")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(num("confidence", "lte", "0.5")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
    }

    @Test
    void numericBoundary_lteAtExactValueTrue_ltAtExactValueFalse() {
        GateSignalView v = new GateSignalView("M", "S", "Sec", 0.8, new BigDecimal("5"));
        assertThat(GateEvaluator.evaluate(gate(num("price", "lte", "5")), v))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(num("price", "lt", "5")), v))
                .contains(new GateEvaluator.MatchResult(false));
    }

    // ---- AND semantics ----

    @Test
    void andSemantics_allTrueMatches_oneFalseNoMatch() {
        var both = gate(str("mechanism", "eq", "INSIDER_CLUSTER"), num("price", "lt", "5"));
        assertThat(GateEvaluator.evaluate(both, FULL))
                .contains(new GateEvaluator.MatchResult(true));
        var oneFalse = gate(str("mechanism", "eq", "INSIDER_CLUSTER"), num("price", "gt", "5"));
        assertThat(GateEvaluator.evaluate(oneFalse, FULL))
                .contains(new GateEvaluator.MatchResult(false));
    }

    // ---- fail-open on null fields ----

    @Test
    void nullSectorMakesSectorConditionUnevaluable_gateDoesNotFire() {
        GateSignalView noSector = new GateSignalView("INSIDER_CLUSTER", "ACME", null, 0.8,
                new BigDecimal("4.50"));
        var g = gate(str("sector", "eq", "Biotechnology"),
                str("mechanism", "eq", "INSIDER_CLUSTER"));
        assertThat(GateEvaluator.evaluate(g, noSector)).isEmpty();
    }

    @Test
    void nullPriceAndNullConfidenceFailOpen() {
        GateSignalView v = new GateSignalView("M", "S", "Sec", null, null);
        assertThat(GateEvaluator.evaluate(gate(num("price", "lt", "5")), v)).isEmpty();
        assertThat(GateEvaluator.evaluate(gate(num("confidence", "gt", "0.5")), v)).isEmpty();
    }

    @Test
    void negativeOpsOnNullFieldAreNotTriviallyTrue() {
        GateSignalView noSector = new GateSignalView("M", "S", null, 0.8, BigDecimal.ONE);
        assertThat(GateEvaluator.evaluate(gate(str("sector", "ne", "Biotechnology")), noSector))
                .isEmpty();
        assertThat(GateEvaluator.evaluate(gate(str("sector", "not_in", "Biotechnology")), noSector))
                .isEmpty();
    }

    @Test
    void definiteFalseWinsOverUnevaluableSibling() {
        GateSignalView noSector = new GateSignalView("PEAD", "S", null, 0.8, BigDecimal.ONE);
        var g = gate(str("mechanism", "eq", "INSIDER_CLUSTER"), str("sector", "eq", "Bio"));
        assertThat(GateEvaluator.evaluate(g, noSector))
                .contains(new GateEvaluator.MatchResult(false));
    }

    // ---- case sensitivity ----

    @Test
    void sectorComparisonIsCaseInsensitive() {
        assertThat(GateEvaluator.evaluate(gate(str("sector", "eq", "bioTECHnology")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
        assertThat(GateEvaluator.evaluate(gate(str("sector", "in", "BIOTECHNOLOGY")), FULL))
                .contains(new GateEvaluator.MatchResult(true));
    }

    @Test
    void mechanismAndSymbolComparisonsAreExactCase() {
        assertThat(GateEvaluator.evaluate(gate(str("mechanism", "eq", "insider_cluster")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
        assertThat(GateEvaluator.evaluate(gate(str("symbol", "eq", "acme")), FULL))
                .contains(new GateEvaluator.MatchResult(false));
    }
}
