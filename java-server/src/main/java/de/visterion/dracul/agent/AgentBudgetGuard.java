package de.visterion.dracul.agent;

import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.vistierie.BudgetStatus;
import de.visterion.dracul.vistierie.VistierieClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup guard for a prod incident (2026-06-21): a scheduled agent without a
 * Vistierie budget silently never runs — Vistierie throws
 * {@code BudgetException: agent budget missing} on any pause toggle, and
 * {@code GET /agents/{name}/budget} returns 200 with all-null caps, masking the
 * problem. This walks every enabled, scheduled agent definition at startup and
 * flags the ones with no budget configured, so it surfaces in the Chronicle
 * settings UI instead of failing silently later.
 */
@Component
public class AgentBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(AgentBudgetGuard.class);
    static final String SETTINGS_KEY = "health.agent_budgets";

    private final VistierieClient vistierie;
    private final AgentDefinitionStore store;
    private final AppSettingsRepository settings;

    public AgentBudgetGuard(VistierieClient vistierie, AgentDefinitionStore store,
                             AppSettingsRepository settings) {
        this.vistierie = vistierie;
        this.store = store;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAll() {
        List<String> missing = new ArrayList<>();
        for (var def : store.findAllEnabled()) {
            if (def.schedule() == null) continue; // trigger-only agents don't need a budget
            if (isMissing(def.name())) missing.add(def.name());
        }

        if (missing.isEmpty()) {
            settings.put(SETTINGS_KEY, "OK");
        } else {
            for (var name : missing) {
                log.warn("{} is scheduled but has no Vistierie budget configured", name);
            }
            settings.put(SETTINGS_KEY, "MISSING:" + String.join(",", missing));
        }
    }

    private boolean isMissing(String name) {
        BudgetStatus budget;
        try {
            budget = vistierie.getAgentBudget(name);
        } catch (Exception e) {
            log.warn("{} budget lookup failed ({}); treating as missing", name, e.getMessage());
            return true;
        }
        return budget == null
                || (budget.dailyCapMicros() == null && budget.monthlyCapMicros() == null);
    }
}
