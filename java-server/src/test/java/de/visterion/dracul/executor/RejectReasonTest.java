package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RejectReasonTest {

    /** The exact set of temporary capacity/rate vetos whose signal stays PENDING for retry. */
    private static final Set<RejectReason> EXPECTED_TRANSIENT = EnumSet.of(
            RejectReason.PACE_LIMIT,
            RejectReason.MAX_POSITIONS,
            RejectReason.BUDGET,
            RejectReason.HEAT_LIMIT,
            RejectReason.COOLDOWN,
            RejectReason.PATTERN_GATE);

    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void isTransient_matchesExactlyTheCapacityRateSet(RejectReason reason) {
        assertThat(reason.isTransient())
                .as("isTransient() for " + reason)
                .isEqualTo(EXPECTED_TRANSIENT.contains(reason));
    }

    @Test
    void terminalSamplesAreNotTransient() {
        assertThat(RejectReason.LOW_CONFIDENCE.isTransient()).isFalse();
        assertThat(RejectReason.SCHEMA_INVALID.isTransient()).isFalse();
        assertThat(RejectReason.SIGNAL_EXPIRED.isTransient()).isFalse();
        assertThat(RejectReason.CHASED_AWAY.isTransient()).isFalse();
        assertThat(RejectReason.CONTRADICTION.isTransient()).isFalse();
    }
}
