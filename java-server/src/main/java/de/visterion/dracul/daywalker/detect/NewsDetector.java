package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.watchlist.WatchlistItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fires NEGATIVE_NEWS when the supplied headline list is non-empty. The caller
 * bounds the headlines by date window; repeated re-emission of the same day's
 * headline is suppressed by the per-(symbol, trigger_type) cooldown, not here.
 * Negativity itself is judged by the LLM child run, not here.
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
