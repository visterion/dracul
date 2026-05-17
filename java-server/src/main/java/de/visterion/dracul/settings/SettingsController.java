package de.visterion.dracul.settings;

import de.visterion.dracul.vistierie.BudgetPatch;
import de.visterion.dracul.vistierie.BudgetStatus;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final List<String> STRIGOI_NAMES = List.of(
            "strigoi-spin", "strigoi-insider", "strigoi-echo",
            "strigoi-lazarus", "strigoi-index", "strigoi-merger"
    );

    private final VistierieClient client;

    public SettingsController(VistierieClient client) {
        this.client = client;
    }

    @GetMapping("/budgets")
    public SettingsBudgetData getBudgets() {
        var tenant = client.getTenantBudget();
        var agents = STRIGOI_NAMES.stream()
                .map(name -> new SettingsBudgetData.AgentBudget(name, client.getAgentBudget(name)))
                .toList();
        return new SettingsBudgetData(tenant, agents);
    }

    @PatchMapping("/budgets")
    public BudgetStatus patchTenantBudget(@RequestBody BudgetPatch patch) {
        return client.patchTenantBudget(patch);
    }

    @PatchMapping("/budgets/agents/{name}")
    public BudgetStatus patchAgentBudget(@PathVariable String name,
                                          @RequestBody BudgetPatch patch) {
        return client.patchAgentBudget(name, patch);
    }
}
