package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.rule-version=exec-test-1"
})
class RuleVersionProviderTest {

    @Autowired RuleVersionProvider provider;
    @Autowired RuleVersionRepository repo;

    @Test
    void seedsActiveVersionOnStartup() {
        assertThat(provider.active()).isEqualTo("exec-test-1");
        assertThat(repo.exists("exec-test-1")).isTrue();

        var v = repo.find("exec-test-1");
        assertThat(v).isNotNull();
        assertThat(v.params().path("chandelier_mult").asDouble()).isEqualTo(3.0);
        assertThat(v.params().path("confidence_min").asDouble()).isEqualTo(0.65);
        assertThat(v.params().path("trim_fractions").asString()).isEqualTo("0.33,0.5,1.0");
        assertThat(v.params().path("entry_gtd_days").asInt()).isEqualTo(2);
        assertThat(v.params().path("kill_criteria_hard").asString()).isEqualTo("price-level only");
        // SP1 parameters — the seeded row is the permanent record of what this version changed.
        assertThat(v.params().path("broker_stop_buffer_atr").asDouble()).isEqualTo(1.0);
        assertThat(v.params().path("max_broker_stop_pct").asDouble()).isEqualTo(0.20);
        assertThat(v.params().path("atr_short_period").asInt()).isEqualTo(5);
        assertThat(v.params().path("risk_pct").asDouble()).isEqualTo(0.01);
    }

    /** The `changes` text is written exactly once per version string and is then permanent, so it
     *  is the audit record of what exec-v0.5 changed. Prod verification §6.4(a) asserts this
     *  string; pin it here so a later edit to the code cannot drift it silently.
     *  Mutation: any word changed in the changes string. */
    @Test
    void changesTextIsTheSpecifiedOne() {
        var v = repo.find("exec-test-1");
        assertThat(v.changes()).isEqualTo(
                "buffered broker stop (monotonic, capped); atr_short with atrEff on stop window, "
                        + "buffer and chandelier; risk-based sizing with RISK_TOO_WIDE; heat stays "
                        + "on logical position_risk, position_risk_broker logged only; tranche2 "
                        + "without NEW_HIGH, gated on entry_filled_at");
    }
}
