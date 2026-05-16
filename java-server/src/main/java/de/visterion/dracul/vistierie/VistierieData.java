package de.visterion.dracul.vistierie;

import java.util.List;

public record VistierieData(
        List<TierBudget> tiers,
        List<AgentSpend> spendingByAgent,
        List<DailySpend> dailySpend30d,
        double monthlyTotalUsd,
        double monthlyBudgetUsd
) {
    public record TierBudget(String name, String models, double budgetUsd, double usedUsd) {}
    public record AgentSpend(String agent, double totalUsd, int pct) {}
    public record DailySpend(String date, double totalUsd) {}
}
