package de.visterion.dracul.strigoi;

import java.util.List;

public record StrigoiConfiguration(
        String cron, String nextRunAt, boolean disabled, String tier,
        List<String> allowedModels,
        double dailyBudgetUsd, double dailyUsedUsd,
        double monthlyBudgetUsd, double monthlyUsedUsd,
        String primaryProvider, String fallbackProvider) {}
