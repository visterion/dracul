package de.visterion.dracul.vistierie;

public record VistierieRunDetail(
        String id,
        String agentName,
        String status,
        String startedAt,
        String finishedAt,
        String summary,
        String error
) {}
