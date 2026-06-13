package de.visterion.dracul.pattern;

public record PatternCase(
        String symbol, String companyName, String anomalyType, String occurredAt,
        boolean supported, Double returnPercent, String note) {}
