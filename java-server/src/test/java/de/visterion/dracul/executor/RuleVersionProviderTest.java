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

    static final String CHANGES = "on top of exec-v1.3: CONVICTION take-profit switch "
            + "(dracul.executor.profiles.conviction.take-profit-enabled, default false) -- with it off a "
            + "CONVICTION position exits only by a flagged catastrophe (HARD_CATASTROPHE) or the 35 % "
            + "emergency stop: no HARD_TARGET_HALF, so the 30 % trail, which arms only after a half-sale, "
            + "never arms; a position half-sold before the switch-off keeps its trail; with it on the "
            + "exec-v1.3 lifecycle is unchanged; all other exec-v1.3 gates unchanged";

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
            assertThat(v.params().path("max_positions").asInt()).isEqualTo(35);
            assertThat(v.params().path("mechanism_budget_pct").asString())
                    .isEqualTo("MERGER_ARB:0.20,QUALITY_52W_LOW:0.15,TECH_CONVICTION:0.50,MOMENTUM_12_1:0.28");
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
            assertThat(v.params().path("exit_profiles").asString()).isEqualTo("STANDARD,CONVICTION,MOMENTUM");
            assertThat(v.params().path("conviction_emergency_stop_pct").decimalValue()).isEqualByComparingTo("0.35");
            assertThat(v.params().path("conviction_target_pct").decimalValue()).isEqualByComparingTo("0.30");
            assertThat(v.params().path("conviction_target_fraction").decimalValue()).isEqualByComparingTo("0.5");
            assertThat(v.params().path("conviction_trail_pct").decimalValue()).isEqualByComparingTo("0.30");
            assertThat(v.params().path("conviction_min_entry_qty").asInt()).isEqualTo(2);
            assertThat(v.params().path("conviction_entry_broker_stop_pct").decimalValue()).isEqualByComparingTo("0.20");
            assertThat(v.params().path("conviction_position_pct").decimalValue()).isEqualByComparingTo("0.03");
            // Exit profile MOMENTUM (exec-v1.2)
            assertThat(v.params().path("momentum_position_pct").decimalValue()).isEqualByComparingTo("0.025");
            assertThat(v.params().path("momentum_min_entry_qty").asInt()).isEqualTo(1);
            // CONVICTION take-profit switch (exec-v1.4), default profile = disabled
            assertThat(v.params().path("conviction_take_profit_enabled").asBoolean()).isFalse();
            // Tech-Sparplan (exec-v1.3)
            assertThat(v.params().path("savings_plan_monthly_pct").decimalValue()).isEqualByComparingTo("0.02");
            assertThat(v.params().path("savings_plan_max_position_pct").decimalValue()).isEqualByComparingTo("0.08");
            assertThat(v.params().path("savings_plan_basket_cap_pct").decimalValue()).isEqualByComparingTo("0.50");
            assertThat(v.params().path("savings_plan_limit_premium_pct").decimalValue()).isEqualByComparingTo("0.02");
            assertThat(v.params().path("savings_plan_catch_up_weekdays").asInt()).isEqualTo(3);
            assertThat(v.params().path("savings_plan_window_start_utc").asString()).isEqualTo("21:15");
            assertThat(v.params().path("savings_plan_tif").asString()).isEqualTo("gtc");
            assertThat(v.params().path("savings_plan_place_first").asBoolean()).isFalse();
            assertThat(v.params().path("conviction_r_per_share").asString())
                    .isEqualTo("entry_price x emergency_stop_pct");
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
