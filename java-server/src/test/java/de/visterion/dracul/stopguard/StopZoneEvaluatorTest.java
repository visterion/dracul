package de.visterion.dracul.stopguard;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StopZoneEvaluatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void priceBelowStopIsBreached() {
        assertThat(StopZoneEvaluator.evaluate(bd("90"), bd("100"), bd("10"), 0.5))
                .isEqualTo(StopZone.BREACHED);
    }

    @Test
    void priceEqualToStopIsBreached() {
        assertThat(StopZoneEvaluator.evaluate(bd("100"), bd("100"), bd("10"), 0.5))
                .isEqualTo(StopZone.BREACHED);
    }

    @Test
    void priceInsideBandIsProximity() {
        // stop 100, atr 10, mult 0.5 -> band top = 105
        assertThat(StopZoneEvaluator.evaluate(bd("103"), bd("100"), bd("10"), 0.5))
                .isEqualTo(StopZone.PROXIMITY);
    }

    @Test
    void priceAtBandTopIsProximity() {
        assertThat(StopZoneEvaluator.evaluate(bd("105"), bd("100"), bd("10"), 0.5))
                .isEqualTo(StopZone.PROXIMITY);
    }

    @Test
    void priceAboveBandIsNone() {
        assertThat(StopZoneEvaluator.evaluate(bd("106"), bd("100"), bd("10"), 0.5))
                .isEqualTo(StopZone.NONE);
    }

    @Test
    void multiplierWidensTheBand() {
        // mult 1.0 -> band top = 110; 108 now inside
        assertThat(StopZoneEvaluator.evaluate(bd("108"), bd("100"), bd("10"), 1.0))
                .isEqualTo(StopZone.PROXIMITY);
    }

    @Test
    void nonPositiveAtrCollapsesProximityBand() {
        // atr 0 -> band top = stop; price above stop -> NONE, price at/below stop -> BREACHED
        assertThat(StopZoneEvaluator.evaluate(bd("103"), bd("100"), bd("0"), 0.5))
                .isEqualTo(StopZone.NONE);
        assertThat(StopZoneEvaluator.evaluate(bd("100"), bd("100"), bd("0"), 0.5))
                .isEqualTo(StopZone.BREACHED);
    }

    @Test
    void nullInputsAreNone() {
        assertThat(StopZoneEvaluator.evaluate(null, bd("100"), bd("10"), 0.5)).isEqualTo(StopZone.NONE);
        assertThat(StopZoneEvaluator.evaluate(bd("100"), null, bd("10"), 0.5)).isEqualTo(StopZone.NONE);
        assertThat(StopZoneEvaluator.evaluate(bd("100"), bd("100"), null, 0.5)).isEqualTo(StopZone.NONE);
    }
}
