package de.visterion.dracul.daywalker.detect;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class BreachedLevelTest {

    @Test void stopWhenCloseAtOrBelowActiveStop() {
        assertThat(BreachedLevel.evaluate(new BigDecimal("92"), new BigDecimal("92"), new BigDecimal("120")))
                .isEqualTo("STOP");
        assertThat(BreachedLevel.evaluate(new BigDecimal("90"), new BigDecimal("92"), new BigDecimal("120")))
                .isEqualTo("STOP");
    }

    @Test void targetWhenCloseAtOrAboveNextTarget() {
        assertThat(BreachedLevel.evaluate(new BigDecimal("120"), new BigDecimal("92"), new BigDecimal("120")))
                .isEqualTo("TARGET");
    }

    @Test void nullWhenBetweenOrLevelsMissing() {
        assertThat(BreachedLevel.evaluate(new BigDecimal("100"), new BigDecimal("92"), new BigDecimal("120")))
                .isNull();
        assertThat(BreachedLevel.evaluate(new BigDecimal("100"), null, null)).isNull();
        assertThat(BreachedLevel.evaluate(null, new BigDecimal("92"), new BigDecimal("120"))).isNull();
    }
}
