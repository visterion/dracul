package de.visterion.dracul.executor;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ensures the configured active rule version exists in {@code rule_versions}, seeding it on first boot.
 *
 * <p>{@code @DependsOn("flyway")} is required: this bean's {@code @PostConstruct} queries the
 * database eagerly during context startup, and {@code FlywayConfig}'s hand-rolled {@code Flyway}
 * bean (not Spring Boot's auto-configured one) carries no automatic depends-on wiring against
 * {@code JdbcClient} consumers — without this, migrations may not have run yet.
 */
@Component
@DependsOn("flyway")
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class RuleVersionProvider {

    private final String active;
    private final RuleVersionRepository repo;
    private final ObjectMapper mapper;
    private final BigDecimal brokerStopBufferAtr;
    private final BigDecimal maxBrokerStopPct;
    private final int atrShortPeriod;
    private final double riskPct;
    private final double minConfidence;
    private final int maxPositions;
    private final MechanismBudget mechanismBudget;

    public RuleVersionProvider(
            @Value("${dracul.executor.rule-version:exec-v0.6}") String active,
            RuleVersionRepository repo,
            ObjectMapper mapper,
            @Value("${dracul.executor.broker-stop-buffer-atr:1.0}") BigDecimal brokerStopBufferAtr,
            @Value("${dracul.executor.max-broker-stop-pct:0.20}") BigDecimal maxBrokerStopPct,
            @Value("${dracul.executor.atr-short-period:5}") int atrShortPeriod,
            @Value("${dracul.executor.risk-pct:0.01}") double riskPct,
            @Value("${dracul.executor.min-confidence:0.40}") double minConfidence,
            @Value("${dracul.executor.max-positions:8}") int maxPositions,
            MechanismBudget mechanismBudget) {
        this.active = active;
        this.repo = repo;
        this.mapper = mapper;
        this.brokerStopBufferAtr = brokerStopBufferAtr;
        this.maxBrokerStopPct = maxBrokerStopPct;
        this.atrShortPeriod = atrShortPeriod;
        this.riskPct = riskPct;
        this.minConfidence = minConfidence;
        this.maxPositions = maxPositions;
        this.mechanismBudget = mechanismBudget;
    }

    @PostConstruct
    void seed() {
        if (!repo.exists(active)) {
            var params = mapper.createObjectNode()
                    .put("chandelier_mult", 3.0)
                    .put("giveback_pct", 0.35)
                    .put("giveback_active_from_r", 1.5)
                    .put("cooldown_days", 10)
                    .put("atr_period", 22)
                    .put("soft_confirm_min", 2)
                    .put("confidence_min", minConfidence)
                    .put("max_positions", maxPositions)
                    .put("trim_fractions", "0.33,0.5,1.0")
                    .put("entry_gtd_days", 2)
                    .put("kill_criteria_hard", "price-level only")
                    .put("broker_stop_buffer_atr", brokerStopBufferAtr)
                    .put("max_broker_stop_pct", maxBrokerStopPct)
                    .put("atr_short_period", atrShortPeriod)
                    .put("risk_pct", riskPct)
                    .put("mechanism_budget_pct", mechanismBudget.spec());
            // seed() only inserts when the version string is NEW, so this text is written once and
            // is then permanent for exec-v0.6 -- it is the audit record of what this version
            // changed, and prod verification asserts it verbatim.
            repo.upsert(new RuleVersion(active, LocalDate.now().toString(),
                    "confidence floor 0.40; confidence withheld from the LLM queue and dropped "
                            + "from ranking (freshness first); MECHANISM_BUDGET entry cap (MERGER_ARB 20%, "
                            + "QUALITY_52W_LOW 15% of budget), transient like MAX_POSITIONS; max_positions 8",
                    null, params));
        }
    }

    public String active() {
        return active;
    }
}
