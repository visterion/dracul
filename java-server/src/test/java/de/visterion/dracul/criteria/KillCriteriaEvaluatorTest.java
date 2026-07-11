package de.visterion.dracul.criteria;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KillCriteriaEvaluatorTest {
    private final KillCriteriaEvaluator eval = new KillCriteriaEvaluator();

    @Test
    void detectsCloseBelow() {
        List<String> b = eval.breached(List.of("Close below $90.00"), new BigDecimal("89.50"));
        assertThat(b).containsExactly("Close below $90.00");
    }

    @Test
    void noBreachWhenAboveThreshold() {
        assertThat(eval.breached(List.of("Close below 90"), new BigDecimal("95"))).isEmpty();
    }

    @Test
    void detectsAboveDirection() {
        assertThat(eval.breached(List.of("Price rises above 120"), new BigDecimal("121")))
                .hasSize(1);
    }

    @Test
    void ignoresUnparseableCriteria() {
        assertThat(eval.breached(
                List.of("Merger terminated", "CEO resigns"), new BigDecimal("50"))).isEmpty();
    }

    @Test
    void ignoresPercentThresholds() {
        // "below 15%" is a ratio, not a price level — must NOT match
        assertThat(eval.breached(List.of("Spread widens above 12%"), new BigDecimal("50")))
                .isEmpty();
    }

    @Test
    void ignoresPercentThresholdEvenWhenPrefixDigitsWouldBreach() {
        // regression: "125%" must not backtrack-match as price level 12 (or 125)
        assertThat(eval.breached(List.of("Price rises above 125%"), new BigDecimal("500")))
                .isEmpty();
    }

    @Test
    void ignoresDecimalPercentThreshold() {
        // regression: "12.5%" must not backtrack-match as price level 12
        assertThat(eval.breached(List.of("Close falls below 12.5%"), new BigDecimal("10")))
                .isEmpty();
        assertThat(eval.breached(List.of("Price falls below 15%"), new BigDecimal("10")))
                .isEmpty();
    }

    @Test
    void detectsDecimalPriceLevel() {
        assertThat(eval.breached(List.of("Close below 12.5"), new BigDecimal("12")))
                .containsExactly("Close below 12.5");
    }

    @Test
    void detectsStopKeyword() {
        assertThat(eval.breached(List.of("Stop below $90"), new BigDecimal("85")))
                .containsExactly("Stop below $90");
    }

    @Test
    void nullSafe() {
        assertThat(eval.breached(null, new BigDecimal("1"))).isEmpty();
        assertThat(eval.breached(List.of("close below 5"), null)).isEmpty();
    }
}
