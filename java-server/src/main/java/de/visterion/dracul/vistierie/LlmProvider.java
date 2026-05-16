package de.visterion.dracul.vistierie;

import java.util.List;

public record LlmProvider(
        String id, String name, String status,
        String apiKeyMasked, String endpoint, List<String> models,
        long todayInputTokens, long todayOutputTokens,
        double todayCostUsd, Integer callsToday) {}
