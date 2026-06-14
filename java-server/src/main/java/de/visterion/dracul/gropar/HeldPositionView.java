package de.visterion.dracul.gropar;

import java.util.List;
import java.util.Map;

/** Tool-response view: a held position enriched with indicators, fired rules, and thesis. */
public record HeldPositionView(
        String symbol,
        String companyName,
        double entryPrice,
        double shareCount,
        double currentPrice,
        ExitIndicators indicators,
        List<String> firedRules,
        Map<String, Object> thesis   // {summary, signals, risks, anomalyTypes, horizon} or null
) {}
