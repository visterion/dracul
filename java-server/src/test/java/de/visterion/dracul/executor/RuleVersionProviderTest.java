package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Testcontainer is reused across classes and runs (ContainerConfig withReuse(true)) and
 *  seed() is insert-if-absent, so each context uses a version string the container has never
 *  seen; valid_from == today proves this run wrote the row. */
class RuleVersionProviderTest {

    static final String DEFAULTS_VERSION = "exec-test-sp2-" + UUID.randomUUID();
    static final String OVERRIDES_VERSION = "exec-test-sp2-ovr-" + UUID.randomUUID();

    static final String CHANGES = "on top of exec-v1.0 (exit profile CONVICTION for mechanism TECH_CONVICTION, "
            + "strigoi-tech basket): (1) post-fill widening -- the entry-band broker "
            + "leg of a filled CONVICTION position is widened to the logical stop by "
            + "the first maintenance pass after the fill (BROKER_STOP_WIDENED); a "
            + "broker rejection escalates BROKER_STOP_WIDEN_REJECTED once, is never "
            + "retried, and leaves the narrow entry-band leg as the effective "
            + "emergency stop; (2) capital split revision 2026-10-05 -- basket-size "
            + "12 -> 10, position-pct 0.033 -> 0.03 (ten names at 3 % each), "
            + "MECHANISM_BUDGET cap TECH_CONVICTION 0.44 -> 0.33 (10 x 3 % + ~10 % "
            + "headroom for EUR/USD drift); all other exec-v1.0 gates (CORRELATED, "
            + "CONCENTRATION and HEAT_LIMIT skipped for the profile, exit_position "
            + "rejects the profile with PROFILE_MANAGED, partial exits repoint the "
            + "leg rows) unchanged";

    @Nested
    @SpringBootTest
    @Import(ContainerConfig.class)
    @ActiveProfiles("dev")
    @TestPropertySource(properties = "dracul.executor.enabled=true")
    class Defaults {
        @DynamicPropertySource
        static void version(DynamicPropertyRegistry r) {
            r.add("dracul.executor.rule-version", () -> DEFAULTS_VERSION);
        }

        @Autowired RuleVersionProvider provider;
        @Autowired RuleVersionRepository repo;

        @Test
        void seedsTheSp2ParamsAndChangesText() {
            assertThat(provider.active()).isEqualTo(DEFAULTS_VERSION);
            var v = repo.find(DEFAULTS_VERSION);
            assertThat(v).isNotNull();
            assertThat(v.validFrom()).isEqualTo(LocalDate.now().toString());
            assertThat(v.changes()).isEqualTo(CHANGES);
            assertThat(v.params().path("confidence_min").asDouble()).isEqualTo(0.4);
            assertThat(v.params().path("max_positions").asInt()).isEqualTo(25);
            assertThat(v.params().path("mechanism_budget_pct").asString())
                    .isEqualTo("MERGER_ARB:0.20,QUALITY_52W_LOW:0.15,TECH_CONVICTION:0.33");
            // SP1 parameters still recorded
            assertThat(v.params().path("broker_stop_buffer_atr").asDouble()).isEqualTo(1.0);
            assertThat(v.params().path("risk_pct").asDouble()).isEqualTo(0.005);
            // Paper capital scale-up (exec-v0.7) params
            assertThat(v.params().path("total_budget").asDouble()).isEqualTo(100000.0);
            assertThat(v.params().path("tranche_count").asInt()).isEqualTo(25);
            assertThat(v.params().path("heat_pct").asDouble()).isEqualTo(0.15);
            assertThat(v.params().path("pace_per_week").asInt()).isEqualTo(10);
            assertThat(v.params().path("max_per_sector").asInt()).isEqualTo(5);
            // Cooldown shortened (exec-v0.8) params
            assertThat(v.params().path("cooldown_days").asInt()).isEqualTo(3);
            // Structured kill level (exec-v0.9)
            assertThat(v.params().path("kill_criteria_hard").asString()).isEqualTo(
                    "structured kill_close_below, single close, KILL_LEVEL_BREACHED veto, "
                            + "0.5 ATR min distance");
            // Exit profile CONVICTION (exec-v1.0)
            assertThat(v.params().path("exit_profiles").asString()).isEqualTo("STANDARD,CONVICTION");
            assertThat(v.params().path("conviction_emergency_stop_pct").decimalValue()).isEqualByComparingTo("0.35");
            assertThat(v.params().path("conviction_target_pct").decimalValue()).isEqualByComparingTo("0.30");
            assertThat(v.params().path("conviction_target_fraction").decimalValue()).isEqualByComparingTo("0.5");
            assertThat(v.params().path("conviction_trail_pct").decimalValue()).isEqualByComparingTo("0.30");
            assertThat(v.params().path("conviction_min_entry_qty").asInt()).isEqualTo(2);
            assertThat(v.params().path("conviction_entry_broker_stop_pct").decimalValue()).isEqualByComparingTo("0.20");
            assertThat(v.params().path("conviction_position_pct").decimalValue()).isEqualByComparingTo("0.03");
        }
    }

    @Nested
    @SpringBootTest
    @Import(ContainerConfig.class)
    @ActiveProfiles("dev")
    @TestPropertySource(properties = {
            "dracul.executor.enabled=true",
            "dracul.executor.min-confidence=0.55",
            "dracul.executor.max-positions=3",
            "dracul.executor.mechanism-budget-pct=X:0.5",
            "dracul.executor.cooldown-days=7"})
    class Overrides {
        @DynamicPropertySource
        static void version(DynamicPropertyRegistry r) {
            r.add("dracul.executor.rule-version", () -> OVERRIDES_VERSION);
        }

        @Autowired RuleVersionRepository repo;
        @Autowired MechanismBudget budget;

        @Test
        void paramsFollowConfiguredThresholds() {
            var v = repo.find(OVERRIDES_VERSION);
            assertThat(v.params().path("confidence_min").asDouble()).isEqualTo(0.55);
            assertThat(v.params().path("max_positions").asInt()).isEqualTo(3);
            assertThat(v.params().path("mechanism_budget_pct").asString()).isEqualTo("X:0.5");
            assertThat(v.params().path("cooldown_days").asInt()).isEqualTo(7);
            assertThat(budget.spec()).isEqualTo("X:0.5");   // the single bean feeds both consumers
        }
    }
}
