package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.agora.RecommendationTrend;
import de.visterion.dracul.watchlist.WatchlistItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fires ANALYST_DOWNGRADE when the latest recommendation period is more bearish
 * than the prior one (a higher sell-weighted score).
 */
public class DowngradeDetector {

    public Optional<TriggerEvent> detect(WatchlistItem item, List<RecommendationTrend> trends) {
        if (trends.size() < 2) return Optional.empty();
        RecommendationTrend latest = trends.get(0);
        RecommendationTrend prior = trends.get(1);
        if (bearScore(latest) <= bearScore(prior)) return Optional.empty();
        return Optional.of(new TriggerEvent(item.ticker(), item.companyName(),
                TriggerType.ANALYST_DOWNGRADE, BigDecimal.valueOf(item.currentPrice()),
                Map.of("period", latest.period(),
                        "sell", latest.sell() + latest.strongSell(),
                        "prior_sell", prior.sell() + prior.strongSell())));
    }

    private static double bearScore(RecommendationTrend t) {
        int total = t.strongBuy() + t.buy() + t.hold() + t.sell() + t.strongSell();
        if (total == 0) return 0.0;
        return (t.sell() + 2.0 * t.strongSell()) / total;
    }
}
