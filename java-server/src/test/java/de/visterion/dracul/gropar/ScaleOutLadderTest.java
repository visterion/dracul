package de.visterion.dracul.gropar;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ScaleOutLadderTest {

    @Test void laddersAtTwoAndFourR() {
        List<BigDecimal> targets = ScaleOutLadder.profitTargets(
                new BigDecimal("100"), new BigDecimal("10"));
        assertThat(targets).containsExactly(new BigDecimal("120"), new BigDecimal("140"));
        assertThat(ScaleOutLadder.SCALE_OUT_FRACTIONS)
                .containsExactly(new BigDecimal("0.3333"), new BigDecimal("0.3333"));
    }

    @Test void emptyWhenRNull() {
        assertThat(ScaleOutLadder.profitTargets(new BigDecimal("100"), null)).isEmpty();
    }
}
