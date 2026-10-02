package de.visterion.dracul.prey;

import java.util.List;

public record Prey(
        String id, String symbol, String companyName, String anomalyType,
        double confidence, String thesis, List<String> signals, List<String> risks,
        List<String> killCriteria, String horizon, String discoveredBy, String discoveredAt,
        /** Structured, code-enforced kill level (V51): one daily close strictly below it kills
         *  the thesis. Null = the hunter set none (or PreyMapper rejected the value). */
        java.math.BigDecimal killCloseBelow) {}
