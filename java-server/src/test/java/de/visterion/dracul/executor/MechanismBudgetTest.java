package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MechanismBudgetTest {

    @Test
    void parsesTheDefaultSpec() {
        MechanismBudget b = new MechanismBudget("MERGER_ARB:0.20,QUALITY_52W_LOW:0.15");
        assertThat(b.shareFor("MERGER_ARB")).contains(0.20);
        assertThat(b.shareFor("QUALITY_52W_LOW")).contains(0.15);
        assertThat(b.shareFor("PEAD")).isEmpty();
        assertThat(b.spec()).isEqualTo("MERGER_ARB:0.20,QUALITY_52W_LOW:0.15");
        assertThat(b.isEmpty()).isFalse();
    }

    @Test
    void acceptsFractionExactlyOne() {
        assertThat(new MechanismBudget("X:1.0").shareFor("X")).contains(1.0);
    }

    @Test
    void normalisesCaseAndWhitespace() {
        MechanismBudget b = new MechanismBudget(" merger_arb : 0.2 ");
        assertThat(b.shareFor("MERGER_ARB")).contains(0.2);
        assertThat(b.shareFor("merger_arb")).contains(0.2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankSpecIsNone(String spec) {
        MechanismBudget b = new MechanismBudget(spec);
        assertThat(b.isEmpty()).isTrue();
        assertThat(b.shareFor("MERGER_ARB")).isEmpty();
        assertThat(b.spec()).isEqualTo("");
    }

    @Test
    void noneIsEmpty() {
        assertThat(MechanismBudget.none().isEmpty()).isTrue();
        assertThat(MechanismBudget.none().shareFor("anything")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MERGER_ARB", "MERGER_ARB:", ":0.2", "MERGER_ARB:abc", "MERGER_ARB:0",
            "MERGER_ARB:-0.1", "MERGER_ARB:1.5", "MERGER_ARB:0.2,merger_arb:0.3"})
    void rejectsMalformedSpec(String spec) {
        assertThatThrownBy(() -> new MechanismBudget(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mechanism-budget-pct");
    }

    @Test
    void shareForNullMechanismIsEmpty() {
        assertThat(new MechanismBudget("MERGER_ARB:0.2").shareFor(null)).isEmpty();
    }
}
