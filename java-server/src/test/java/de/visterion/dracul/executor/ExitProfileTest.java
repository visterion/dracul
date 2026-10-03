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
    void everythingElseIsStandard() {
        assertThat(ExitProfile.fromMechanism(null)).isEqualTo(ExitProfile.STANDARD);
        assertThat(ExitProfile.fromMechanism("")).isEqualTo(ExitProfile.STANDARD);
        assertThat(ExitProfile.fromMechanism("PEAD")).isEqualTo(ExitProfile.STANDARD);
        assertThat(ExitProfile.fromMechanism("TECH_CONVICTION_X")).isEqualTo(ExitProfile.STANDARD);
    }
}
