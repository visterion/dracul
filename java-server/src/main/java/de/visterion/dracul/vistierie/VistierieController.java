package de.visterion.dracul.vistierie;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;

@RestController
@RequestMapping("/api/vistierie")
public class VistierieController {

    private final VistierieProperties props;
    private final VistierieClient client;

    public VistierieController(VistierieProperties props, VistierieClient client) {
        this.props = props;
        this.client = client;
    }

    @GetMapping
    public VistierieData get() {
        var providers = client.getProviders();
        double totalCostUsd = providers.stream().mapToDouble(LlmProvider::todayCostUsd).sum();

        // Tiers: budgets from config, usedUsd approximated as total provider cost split equally
        // (real per-tier tracking requires Vistierie API — not available yet)
        double totalTierBudget = props.tiers().stream()
                .mapToDouble(VistierieProperties.TierConfig::budgetUsd).sum();
        var tiers = props.tiers().stream()
                .map(t -> new VistierieData.TierBudget(t.name(), t.models(), t.budgetUsd(),
                        totalTierBudget > 0
                                ? Math.min(totalCostUsd * (t.budgetUsd() / totalTierBudget), t.budgetUsd())
                                : 0.0))
                .toList();

        // Agent spending: from strigoi detail configurations
        var strigoi = client.listStrigoi();
        var agentSpends = new ArrayList<VistierieData.AgentSpend>();
        double agentTotal = 0.0;
        for (var s : strigoi) {
            var detail = client.getStrigoiDetail(s.name());
            double cost = detail.map(d -> d.configuration().dailyUsedUsd()).orElse(0.0);
            agentSpends.add(new VistierieData.AgentSpend(s.name(), cost, 0));
            agentTotal += cost;
        }
        // Add non-strigoi agents
        agentSpends.add(new VistierieData.AgentSpend("voievod", 0.0, 0));
        agentSpends.add(new VistierieData.AgentSpend("daywalker", 0.0, 0));
        agentTotal = Math.max(agentTotal, 0.01); // avoid division by zero

        // Compute percentages
        final double finalTotal = agentTotal;
        var agentSpendsWithPct = agentSpends.stream()
                .map(a -> new VistierieData.AgentSpend(a.agent(), a.totalUsd(),
                        (int) Math.round(a.totalUsd() / finalTotal * 100)))
                .toList();

        var daily = client.getDashboardData();

        return new VistierieData(tiers, agentSpendsWithPct, daily, totalCostUsd, props.monthlyBudgetUsd());
    }
}
