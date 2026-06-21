package de.visterion.dracul.gropar;

import java.util.List;
import java.util.Map;

/** Tool-response view: a held position enriched with indicators, fired rules, and thesis.
 *  {@code positionId} is the opaque watchlist-item id the agent echoes back per signal so
 *  {@code /complete} can route the signal to the position's owner. */
public record HeldPositionView(
        String positionId,
        String symbol,
        String companyName,
        double entryPrice,
        double shareCount,
        double currentPrice,
        ExitIndicators indicators,
        RiskMetrics risk,
        List<String> firedRules,
        Map<String, Object> thesis   // {summary, signals, risks, anomalyTypes, horizon} or null
) {}
