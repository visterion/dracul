package de.visterion.dracul.hunting.finnhub;

import de.visterion.dracul.strigoi.echo.EarningsRevisions;
import de.visterion.dracul.strigoi.echo.RevisionPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Analyst-revision proxy from Finnhub recommendation-trend movement. Net score per period =
 * strongBuy + buy - sell - strongSell; the proxy is latestNet - previousNet (trend is
 * newest-first). One period → flat (0). Reuses {@link FinnhubNewsAdapter} (never throws);
 * an empty trend → {@link EarningsRevisions#unavailable()}.
 */
@Component
public class FinnhubRevisions implements RevisionPort {

    private final FinnhubNewsAdapter news;

    @Autowired
    public FinnhubRevisions(FinnhubNewsAdapter news) { this.news = news; }

    @Override
    public EarningsRevisions revisions(String symbol) {
        List<RecommendationTrend> trend = news.recommendationTrend(symbol);
        if (trend.isEmpty()) return EarningsRevisions.unavailable();
        int latest = net(trend.get(0));
        int proxy = trend.size() >= 2 ? latest - net(trend.get(1)) : 0;
        String direction = proxy > 0 ? "up" : proxy < 0 ? "down" : "flat";
        return new EarningsRevisions(proxy, direction, true);
    }

    private static int net(RecommendationTrend t) {
        return t.strongBuy() + t.buy() - t.sell() - t.strongSell();
    }
}
