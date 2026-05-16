package de.visterion.dracul.prey;

import java.util.List;

public record Prey(
        String id, String symbol, String companyName, String anomalyType,
        double confidence, String thesis, List<String> signals, List<String> risks,
        String horizon, String discoveredBy, String discoveredAt) {}
