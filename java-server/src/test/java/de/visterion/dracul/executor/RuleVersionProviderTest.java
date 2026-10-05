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

    static final String CHANGES = "on top of exec-v1.1: exit profile MOMENTUM for mechanism "
            + "MOMENTUM_12_1 (strigoi-momentum, monthly 12-1 rebalance) -- CONVICTION's wide "
            + "emergency stop (35 % below entry, broker leg at the entry band, widened after the "
            + "fill) and fixed notional per name (momentum position-pct 0.025 of total-budget, "
            + "FX-converted, SIZE_TOO_SMALL below min-entry-qty 1), no take-profit, no target-half, "
            + "no trail, no catastrophe flag, no soft exit, no tranche 2, exit_position rejects it "
            + "with PROFILE_MANAGED; a position flagged by the strigoi-momentum completion is "
            + "flattened fully (HARD_REBALANCE: without a close before the close-null skip, with a "
            + "close after the stop, which wins the reason code); for a MOMENTUM signal, MOMENTUM "
            + "rows with a committed rebalance exit are excluded from MECHANISM_BUDGET, BUDGET and "
            + "MAX_POSITIONS; LOW_CONFIDENCE, CHASED_AWAY and BELOW_ANCHOR skipped for MOMENTUM; "
            + "PACE_LIMIT counts STANDARD entries only and is skipped for CONVICTION and MOMENTUM; "
            + "max_positions 25 -> 35; MECHANISM_BUDGET MOMENTUM_12_1 0.28; executor max_turns "
            + "25 -> 40; all other exec-v1.1 gates unchanged";

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
                    .isEqualTo("MERGER_ARB:0.20,QUALITY_52W_LOW:0.15,TECH_CONVICTION:0.33,MOMENTUM_12_1:0.28");
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
