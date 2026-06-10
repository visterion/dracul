package de.visterion.dracul.settings;

/** One agent's read-only runtime config for the Settings → agent-config section. */
public record AgentConfigRow(
        String name,
        String role,            // anomalyType for hunters; "reviewer" / "daywalker"; "hunter" fallback
        String state,           // resting | hunting | running | paused | budget-hit
        boolean paused,
        String tier,            // model_purpose; null if detail unavailable
        String schedule,        // cron expression; null if none/unavailable
        String nextRunAt,       // ISO instant or null
        double dailyUsedUsd,
        double dailyBudgetUsd,  // 0 when uncapped/unavailable
        String primaryProvider  // null if detail unavailable
) {}
