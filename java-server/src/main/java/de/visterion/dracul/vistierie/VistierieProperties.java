package de.visterion.dracul.vistierie;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "dracul.vistierie")
public record VistierieProperties(
        String url,
        double monthlyBudgetUsd,
        List<TierConfig> tiers
) {
    public record TierConfig(String name, String models, double budgetUsd) {}
}
