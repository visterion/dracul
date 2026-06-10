package de.visterion.dracul.settings;

import de.visterion.dracul.i18n.LanguageChangedEvent;
import de.visterion.dracul.vistierie.BudgetPatch;
import de.visterion.dracul.vistierie.BudgetStatus;
import de.visterion.dracul.vistierie.StrigoiStatus;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final List<String> STRIGOI_NAMES = List.of(
            "strigoi-spin", "strigoi-insider", "strigoi-echo",
            "strigoi-lazarus", "strigoi-index", "strigoi-merger"
    );

    private static final Set<String> ALLOWED_LANGUAGES = Set.of("de", "en");

    private final VistierieClient client;
    private final AppSettingsRepository settings;
    private final ApplicationEventPublisher events;

    public SettingsController(VistierieClient client,
                              AppSettingsRepository settings,
                              ApplicationEventPublisher events) {
        this.client = client;
        this.settings = settings;
        this.events = events;
    }

    @GetMapping("/budgets")
    public SettingsBudgetData getBudgets() {
        var tenant = client.getTenantBudget();
        var agents = STRIGOI_NAMES.stream()
                .map(name -> new SettingsBudgetData.AgentBudget(name, client.getAgentBudget(name)))
                .toList();
        return new SettingsBudgetData(tenant, agents);
    }

    @GetMapping("/agents")
    public List<AgentConfigRow> getAgents() {
        return client.listStrigoi().stream()
                .map(this::toAgentConfigRow)
                .toList();
    }

    private AgentConfigRow toAgentConfigRow(StrigoiStatus s) {
        var detail = client.getStrigoiDetail(s.name());
        if (detail.isPresent()) {
            var c = detail.get().configuration();
            return new AgentConfigRow(
                    s.name(), role(s.name(), detail.get().anomalyType()), s.state(),
                    c.disabled(), c.tier(), emptyToNull(c.cron()), s.nextRunAt(),
                    c.dailyUsedUsd(), c.dailyBudgetUsd(), c.primaryProvider());
        }
        return new AgentConfigRow(
                s.name(), role(s.name(), null), s.state(),
                "paused".equals(s.state()), null, null, s.nextRunAt(),
                0.0, 0.0, null);
    }

    private static String role(String name, String anomalyType) {
        if ("voievod".equals(name)) return "reviewer";
        if ("daywalker".equals(name)) return "daywalker";
        return (anomalyType == null || anomalyType.isBlank()) ? "hunter" : anomalyType;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
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

    @GetMapping("/language")
    public LanguageSetting getLanguage() {
        return new LanguageSetting(settings.getLanguage());
    }

    @PutMapping("/language")
    public ResponseEntity<?> putLanguage(@RequestBody LanguageSetting body) {
        String lang = body.language() == null ? "" : body.language().strip().toLowerCase();
        if (!ALLOWED_LANGUAGES.contains(lang)) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "unsupported language: " + body.language()));
        }
        settings.setLanguage(lang);
        events.publishEvent(new LanguageChangedEvent(lang));
        return ResponseEntity.ok(new LanguageSetting(lang));
    }
}
