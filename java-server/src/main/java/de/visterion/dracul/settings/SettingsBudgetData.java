package de.visterion.dracul.settings;

import de.visterion.dracul.vistierie.BudgetStatus;
import java.util.List;

public record SettingsBudgetData(
        BudgetStatus tenant,
        List<AgentBudget> agents
) {
    public record AgentBudget(String name, BudgetStatus budget) {}
}
