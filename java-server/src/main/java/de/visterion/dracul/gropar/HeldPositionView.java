package de.visterion.dracul.gropar;

import java.util.List;
import java.util.Map;

/** Tool-response view: a held position enriched with indicators, fired rules, and thesis.
 *  {@code positionId} is the opaque watchlist-item id the agent echoes back per signal so
 *  {@code /complete} can route the signal to the position's owner.
 *
 *  <p>{@code currentPrice} is nullable and paired with {@code currentPriceAvailable}, the same
 *  "value + *Available flag" convention {@link ExitIndicators} and {@link RiskMetrics} already
 *  use for every field that can go missing. A position whose price series came back empty is
 *  still rendered -- the operator holds it -- but with an explicit "no price" rather than a
 *  substitute number. */
public record HeldPositionView(
        String positionId,
        String symbol,
        String companyName,
        double entryPrice,
        double shareCount,
        Double currentPrice,
        boolean currentPriceAvailable,
        ExitIndicators indicators,
        RiskMetrics risk,
        List<String> firedRules,
        Map<String, Object> thesis,   // {summary, signals, risks, anomalyTypes, horizon} or null
        List<java.math.BigDecimal> profitTargets,        // [entry+2R, entry+4R] or empty
        List<java.math.BigDecimal> scaleOutFractions      // [0.3333, 0.3333]
) {}
