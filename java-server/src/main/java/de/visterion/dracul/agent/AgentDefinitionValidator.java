package de.visterion.dracul.agent;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Component
public class AgentDefinitionValidator {

    private static final Set<String> MODEL_PURPOSES = Set.of("routine", "reasoning");
    private static final int MAX_TURNS_LIMIT = 200;
    private static final int MAX_RUN_SECONDS_LIMIT = 7200; // 2 hours

    private final AgentToolCatalog catalog;

    public AgentDefinitionValidator(AgentToolCatalog catalog) {
        this.catalog = catalog;
    }

    /** @return Optional error message; empty when valid. */
    public Optional<String> validate(AgentDefinition d) {
        if (d.promptText() == null || d.promptText().isBlank())
            return Optional.of("prompt must not be empty");
        if (!MODEL_PURPOSES.contains(d.modelPurpose()))
            return Optional.of("unsupported model purpose: " + d.modelPurpose());
        if (d.schedule() != null && !CronExpression.isValidExpression(d.schedule()))
            return Optional.of("invalid cron: " + d.schedule());
        if (d.maxTurns() < 1 || d.maxTurns() > MAX_TURNS_LIMIT)
            return Optional.of("max_turns out of range");
        if (d.maxRunSeconds() < 1 || d.maxRunSeconds() > MAX_RUN_SECONDS_LIMIT)
            return Optional.of("max_run_seconds out of range");
        for (var t : d.tools())
            if (!catalog.contains(t.toolName()))
                return Optional.of("unknown tool: " + t.toolName());
        return Optional.empty();
    }
}
