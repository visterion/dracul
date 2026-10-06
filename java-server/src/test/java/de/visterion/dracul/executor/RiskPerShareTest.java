package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2026-10-06 §5.2 "R for CONVICTION" (R2 M10, R3 M3). */
class RiskPerShareTest {

    private static ExecutorPosition position(String side, String entry, String initialStop) {
        return ExecutorPositionFixtures.withoutKillLevel(1L, "c", "SYNTH", side, BigDecimal.TEN,
                entry == null ? null : new BigDecimal(entry),
                initialStop == null ? null : new BigDecimal(initialStop),
                initialStop == null ? null : new BigDecimal(initialStop), 1, null, List.of(),
                "sig-1", "agent", "2026-10-01", null, "OPEN", "brk-1", null, null, 0, null, null,
                null, null, "stop-1", null, null, null, null, 0, null, null, null, null, null,
                null, false, null, "2026-10-01T14:30:00Z");
    }

    @Test
    void convictionUsesTheCurrentAverageTimesTheEmergencyStopPct() {
        // Three falling adds pulled the average to 60, BELOW the immutable initial stop 65:
        // entry − initial_stop would be −5 and flip the sign of every R.
        ExecutorPosition p = ExecutorPositionFixtures.conviction(position("BUY", "60", "65"));
        assertThat(RiskPerShare.of(p, ConvictionProfile.defaults())).isEqualByComparingTo("21.00");
    }

    @Test
    void convictionWithoutAddsIsTheOldValue() {
        ExecutorPosition p = ExecutorPositionFixtures.conviction(position("BUY", "100", "65"));
        assertThat(RiskPerShare.of(p, ConvictionProfile.defaults())).isEqualByComparingTo("35");
    }

    @Test
    void standardKeepsTheSideAwareEntryMinusInitialStop() {
        assertThat(RiskPerShare.of(position("BUY", "100", "95"), ConvictionProfile.defaults()))
                .isEqualByComparingTo("5");
        assertThat(RiskPerShare.of(position("SELL", "100", "105"), ConvictionProfile.defaults()))
                .isEqualByComparingTo("5");
        assertThat(RiskPerShare.of(position("BUY", "100", "100"), ConvictionProfile.defaults()))
                .isEqualByComparingTo("0");
    }

    @Test
    void momentumIsNotConviction() {
        ExecutorPosition p = ExecutorPositionFixtures.momentum(position("BUY", "60", "65"));
        assertThat(RiskPerShare.of(p, ConvictionProfile.defaults())).isEqualByComparingTo("-5");
    }

    @Test
    void missingInputsYieldNull() {
        assertThat(RiskPerShare.of(position("BUY", "100", null), ConvictionProfile.defaults())).isNull();
        assertThat(RiskPerShare.of(ExecutorPositionFixtures.conviction(position("BUY", null, "65")),
                ConvictionProfile.defaults())).isNull();
    }
}
