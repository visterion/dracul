package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExitProfileTest {

    @Test
    void techConvictionMapsToConvictionTrimmedAndCaseInsensitive() {
        assertThat(ExitProfile.fromMechanism("TECH_CONVICTION")).isEqualTo(ExitProfile.CONVICTION);
        assertThat(ExitProfile.fromMechanism("  tech_conviction ")).isEqualTo(ExitProfile.CONVICTION);
        assertThat(ExitProfile.fromMechanism("Tech_Conviction")).isEqualTo(ExitProfile.CONVICTION);
    }

    @Test
    void momentumMapsToMomentumTrimmedAndCaseInsensitive() {
        assertThat(ExitProfile.fromMechanism("MOMENTUM_12_1")).isEqualTo(ExitProfile.MOMENTUM);
        assertThat(ExitProfile.fromMechanism(" momentum_12_1  ")).isEqualTo(ExitProfile.MOMENTUM);
    }

    @Test
    void everythingElseIsStandard() {
        assertThat(ExitProfile.fromMechanism(null)).isEqualTo(ExitProfile.STANDARD);
        assertThat(ExitProfile.fromMechanism("")).isEqualTo(ExitProfile.STANDARD);
        assertThat(ExitProfile.fromMechanism("PEAD")).isEqualTo(ExitProfile.STANDARD);
        assertThat(ExitProfile.fromMechanism("TECH_CONVICTION_X")).isEqualTo(ExitProfile.STANDARD);
        assertThat(ExitProfile.fromMechanism("MOMENTUM")).isEqualTo(ExitProfile.STANDARD);
    }

    /** Spec 2026-10-04 §3: the capability matrix every consumer reads instead of `== CONVICTION`. */
    @Test
    void capabilityMatrix() {
        assertThat(ExitProfile.STANDARD.isWideStop()).isFalse();
        assertThat(ExitProfile.CONVICTION.isWideStop()).isTrue();
        assertThat(ExitProfile.MOMENTUM.isWideStop()).isTrue();

        assertThat(ExitProfile.STANDARD.hasTargetHalf()).isFalse();
        assertThat(ExitProfile.CONVICTION.hasTargetHalf()).isTrue();
        assertThat(ExitProfile.MOMENTUM.hasTargetHalf()).isFalse();

        assertThat(ExitProfile.STANDARD.hasTrail()).isFalse();
        assertThat(ExitProfile.CONVICTION.hasTrail()).isTrue();
        assertThat(ExitProfile.MOMENTUM.hasTrail()).isFalse();

        assertThat(ExitProfile.STANDARD.acceptsCatastropheFlag()).isFalse();
        assertThat(ExitProfile.CONVICTION.acceptsCatastropheFlag()).isTrue();
        assertThat(ExitProfile.MOMENTUM.acceptsCatastropheFlag()).isFalse();
    }

    @Test
    void aRecordWithoutAProfileIsStandard() {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(1L, "c", "SYNA", "BUY",
                java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, java.math.BigDecimal.ONE,
                java.math.BigDecimal.ONE, 1, null, java.util.List.of(), null, null, null, null,
                "OPEN", null, null, null, 0, null, null, null, null, null, null, null, null, null,
                0, null, null, null, null, null, null, false, null, null);
        assertThat(ExecutorPositionFixtures.withProfileFields(base, null, null, null, null, false)
                .profile()).isEqualTo(ExitProfile.STANDARD);
    }
}
