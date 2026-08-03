package de.visterion.dracul.hunting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceHealthTest {

    @Test void healthyHasNoDegradationFlags() {
        DataSourceHealth h = DataSourceHealth.healthy("agora");
        assertThat(h.isHealthy()).isTrue();
        assertThat(h.partial()).isFalse();
        assertThat(h.truncated()).isFalse();
    }

    @Test void unavailableHasNoDegradationFlags() {
        DataSourceHealth h = DataSourceHealth.unavailable("agora", "boom");
        assertThat(h.isHealthy()).isFalse();
        assertThat(h.detail()).isEqualTo("boom");
        assertThat(h.partial()).isFalse();
        assertThat(h.truncated()).isFalse();
    }

    /** Degradation is NOT a status change: the hunter prompts stop the agent hard on
     *  status "unavailable", so a partial fetch must stay "healthy" and carry its
     *  degradation in the flags instead. */
    @Test void degradedStaysHealthyButFlagsTheDegradation() {
        DataSourceHealth h = DataSourceHealth.degraded("agora", "earnings window not fully covered", true, false);
        assertThat(h.status()).isEqualTo("healthy");
        assertThat(h.isHealthy()).isTrue();
        assertThat(h.partial()).isTrue();
        assertThat(h.truncated()).isFalse();
        assertThat(h.detail()).isEqualTo("earnings window not fully covered");
    }
}
