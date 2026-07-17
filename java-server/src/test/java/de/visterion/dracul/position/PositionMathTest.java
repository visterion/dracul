package de.visterion.dracul.position;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PositionMathTest {

    @Test void directionFromQuantitySign() {
        assertThat(PositionMath.direction(new BigDecimal("10"))).isEqualTo("long");
        assertThat(PositionMath.direction(new BigDecimal("-10"))).isEqualTo("short");
        assertThat(PositionMath.direction(BigDecimal.ZERO)).isNull();
        assertThat(PositionMath.direction(null)).isNull();
    }

    @Test void gainLossPctLongAndShortSigns() {
        // long: close 110 vs entry 100 -> +10
        assertThat(PositionMath.gainLossPct("long", new BigDecimal("100"), new BigDecimal("110")))
                .isEqualByComparingTo("10");
        // short: close 110 vs entry 100 -> (100-110)/100*100 = -10
        assertThat(PositionMath.gainLossPct("short", new BigDecimal("100"), new BigDecimal("110")))
                .isEqualByComparingTo("-10");
        // null direction -> long math (status quo)
        assertThat(PositionMath.gainLossPct(null, new BigDecimal("100"), new BigDecimal("90")))
                .isEqualByComparingTo("-10");
    }

    @Test void gainLossPctNullSafe() {
        assertThat(PositionMath.gainLossPct("long", null, BigDecimal.ONE)).isNull();
        assertThat(PositionMath.gainLossPct("long", BigDecimal.ONE, null)).isNull();
        assertThat(PositionMath.gainLossPct("long", BigDecimal.ZERO, BigDecimal.ONE)).isNull();
    }

    @Test void perUnitPriceIsAbsoluteAndNullSafe() {
        assertThat(PositionMath.perUnitPrice(new BigDecimal("-1000"), new BigDecimal("-10")))
                .isEqualByComparingTo("100");
        assertThat(PositionMath.perUnitPrice(null, BigDecimal.TEN)).isNull();
        assertThat(PositionMath.perUnitPrice(BigDecimal.TEN, null)).isNull();
        assertThat(PositionMath.perUnitPrice(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
    }
}
