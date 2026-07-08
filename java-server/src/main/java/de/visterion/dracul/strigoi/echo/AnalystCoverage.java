package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.RecommendationTrend;

import java.util.List;

/** Total analyst coverage derived from the latest recommendation-trend period
 *  (strongBuy + buy + hold + sell + strongSell). Low coverage = stronger neglect,
 *  where PEAD drift persists longer. {@code available} false = no trend at all;
 *  {@code coverage} is null in that case. Pure — no I/O. */
public record AnalystCoverage(Integer coverage, boolean available) {

    public static AnalystCoverage of(List<RecommendationTrend> trend) {
        if (trend == null || trend.isEmpty()) return new AnalystCoverage(null, false);
        RecommendationTrend latest = trend.get(0);
        int total = latest.strongBuy() + latest.buy() + latest.hold()
                + latest.sell() + latest.strongSell();
        return new AnalystCoverage(total, true);
    }
}
