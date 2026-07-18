package de.visterion.dracul.pattern;

public record Pattern(
        String id, String appliesToStrigoi, String statement,
        String status, int evidenceCount, String proposedAt,
        Integer supportedCount, Double avgUpliftPercent, String name,
        String gateJson) {}
