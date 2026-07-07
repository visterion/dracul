package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.RecommendationTrend;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Analyst-revision proxy from recommendation-trend movement. Net score per period =
 * strongBuy + buy - sell - strongSell; the proxy is latestNet - previousNet (trend is
 * newest-first). One period → flat (0). Pure — the caller fetches the trend (shared with
 * {@link AnalystCoverage}). Empty trend → {@link EarningsRevisions#unavailable()}.
 */
@Component
public class RevisionsProxy {

    public EarningsRevisions revisions(List<RecommendationTrend> trend) {
        if (trend == null || trend.isEmpty()) return EarningsRevisions.unavailable();
        int latest = net(trend.get(0));
        int proxy = trend.size() >= 2 ? latest - net(trend.get(1)) : 0;
        String direction = proxy > 0 ? "up" : proxy < 0 ? "down" : "flat";
        return new EarningsRevisions(proxy, direction, true);
    }

    private static int net(RecommendationTrend t) {
        return t.strongBuy() + t.buy() - t.sell() - t.strongSell();
    }
}
