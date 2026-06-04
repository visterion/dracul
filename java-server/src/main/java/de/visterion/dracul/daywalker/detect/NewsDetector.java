package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.finnhub.NewsHeadline;
import de.visterion.dracul.watchlist.WatchlistItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fires NEGATIVE_NEWS when a material headline exists for the item since the
 * last poll. Negativity itself is judged by the LLM child run, not here.
 */
public class NewsDetector {

    public Optional<TriggerEvent> detect(WatchlistItem item, List<NewsHeadline> headlines) {
        if (headlines.isEmpty()) return Optional.empty();
        NewsHeadline h = headlines.get(0);
        return Optional.of(new TriggerEvent(item.ticker(), item.companyName(),
                TriggerType.NEGATIVE_NEWS, BigDecimal.valueOf(item.currentPrice()),
                Map.of("headline", h.headline(),
                        "source", h.source(),
                        "url", h.url())));
    }
}
