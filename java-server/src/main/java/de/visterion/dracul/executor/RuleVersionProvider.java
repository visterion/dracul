package de.visterion.dracul.executor;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

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

    public RuleVersionProvider(
            @Value("${dracul.executor.rule-version:exec-v0.4}") String active,
            RuleVersionRepository repo,
            ObjectMapper mapper) {
        this.active = active;
        this.repo = repo;
        this.mapper = mapper;
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
                    .put("confidence_min", 0.65)
                    .put("max_positions", 5)
                    .put("trim_fractions", "0.33,0.5,1.0")
                    .put("entry_gtd_days", 2)
                    .put("kill_criteria_hard", "price-level only");
            repo.upsert(new RuleVersion(active, LocalDate.now().toString(),
                    "sim completion: hybrid kill hard trigger, scale-out ladder, entry GTD; confidence_min drift fixed 0.6->0.65", null, params));
        }
    }

    public String active() {
        return active;
    }
}
