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

    static final String CHANGES = "confidence floor 0.40; confidence withheld from the LLM queue and dropped "
            + "from ranking (freshness first); MECHANISM_BUDGET entry cap (MERGER_ARB 20%, QUALITY_52W_LOW 15% "
            + "of budget), transient like MAX_POSITIONS; max_positions 8";

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
            assertThat(v.params().path("max_positions").asInt()).isEqualTo(8);
            assertThat(v.params().path("mechanism_budget_pct").asString())
                    .isEqualTo("MERGER_ARB:0.20,QUALITY_52W_LOW:0.15");
            // SP1 parameters still recorded
            assertThat(v.params().path("broker_stop_buffer_atr").asDouble()).isEqualTo(1.0);
            assertThat(v.params().path("risk_pct").asDouble()).isEqualTo(0.01);
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
            "dracul.executor.mechanism-budget-pct=X:0.5"})
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
            assertThat(budget.spec()).isEqualTo("X:0.5");   // the single bean feeds both consumers
        }
    }
}
