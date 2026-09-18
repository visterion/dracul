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
    private final BigDecimal totalBudget;
    private final int trancheCount;
    private final double heatPct;
    private final int pacePerWeek;
    private final int maxPerSector;
    private final MechanismBudget mechanismBudget;

    public RuleVersionProvider(
            @Value("${dracul.executor.rule-version:exec-v0.7}") String active,
            RuleVersionRepository repo,
            ObjectMapper mapper,
            @Value("${dracul.executor.broker-stop-buffer-atr:1.0}") BigDecimal brokerStopBufferAtr,
            @Value("${dracul.executor.max-broker-stop-pct:0.20}") BigDecimal maxBrokerStopPct,
            @Value("${dracul.executor.atr-short-period:5}") int atrShortPeriod,
            @Value("${dracul.executor.risk-pct:0.005}") double riskPct,
            @Value("${dracul.executor.min-confidence:0.40}") double minConfidence,
            @Value("${dracul.executor.max-positions:25}") int maxPositions,
            @Value("${dracul.executor.total-budget:100000}") BigDecimal totalBudget,
            @Value("${dracul.executor.tranche-count:25}") int trancheCount,
            @Value("${dracul.executor.heat-pct:0.15}") double heatPct,
            @Value("${dracul.executor.pace-per-week:10}") int pacePerWeek,
            @Value("${dracul.executor.max-per-sector:5}") int maxPerSector,
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
        this.totalBudget = totalBudget;
        this.trancheCount = trancheCount;
        this.heatPct = heatPct;
        this.pacePerWeek = pacePerWeek;
        this.maxPerSector = maxPerSector;
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
                    .put("mechanism_budget_pct", mechanismBudget.spec())
                    .put("total_budget", totalBudget)
                    .put("tranche_count", trancheCount)
                    .put("heat_pct", heatPct)
                    .put("pace_per_week", pacePerWeek)
                    .put("max_per_sector", maxPerSector);
            // seed() only inserts when the version string is NEW, so this text is written once and
            // is then permanent for the version it describes -- it is the audit record of what
            // that version changed, and prod verification asserts it verbatim.
            //
            // exec-v0.6 history (no longer seeded): "confidence floor 0.40; confidence withheld
            // from the LLM queue and dropped from ranking (freshness first); MECHANISM_BUDGET
            // entry cap (MERGER_ARB 20%, QUALITY_52W_LOW 15% of budget), transient like
            // MAX_POSITIONS; max_positions 8"
            repo.upsert(new RuleVersion(active, LocalDate.now().toString(),
                    "paper capital scale-up for learning throughput: total_budget 100000, "
                            + "tranche_count 25 (tranche 4000), risk_pct 0.005, heat_pct 0.15, "
                            + "max_positions 25, pace_per_week 10, max_per_sector 5; mechanism "
                            + "budgets unchanged in pct",
                    null, params));
        }
    }

    public String active() {
        return active;
    }
}
