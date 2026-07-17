package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.hunting.news.NewsEventTagger;
import de.visterion.dracul.hunting.news.NewsEventType;
import de.visterion.dracul.watchlist.WatchlistItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Fires NEGATIVE_NEWS only when at least one headline carries at least one material
 * {@link NewsEventType} tag (T1.3, D2) — the first TAGGED headline wins and its
 * comma-separated wire values ride along as {@code event_tags} in the detail map.
 * Fully untagged headline batches are suppressed with one INFO line (Tag-1 rollout
 * observation hangs on it; volume is at most one line per symbol per sweep). The caller
 * bounds the headlines by date window; repeated re-emission of the same day's headline
 * is suppressed by the per-(symbol, trigger_type) cooldown, not here. Negativity itself
 * is judged by the LLM child run, not here.
 */
public class NewsDetector {

    private static final Logger log = LoggerFactory.getLogger(NewsDetector.class);

    private final NewsEventTagger tagger = new NewsEventTagger();

    public Optional<TriggerEvent> detect(WatchlistItem item, List<NewsHeadline> headlines) {
        if (headlines.isEmpty()) return Optional.empty();
        for (NewsHeadline h : headlines) {
            var tags = tagger.tag(h);
            if (tags.isEmpty()) continue;
            String eventTags = tags.stream()
                    .map(NewsEventType::wireValue)
                    .collect(Collectors.joining(","));
            // LinkedHashMap + ""-defaults: Map.of would NPE on null source/url (R1-m6).
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("headline", h.headline() == null ? "" : h.headline());
            detail.put("source", h.source() == null ? "" : h.source());
            detail.put("url", h.url() == null ? "" : h.url());
            detail.put("event_tags", eventTags);
            return Optional.of(TriggerEvent.watchOnly(item.ticker(), item.companyName(),
                    TriggerType.NEGATIVE_NEWS, BigDecimal.valueOf(item.currentPrice()), detail));
        }
        log.info("news: {} untagged headlines suppressed for {}", headlines.size(), item.ticker());
        return Optional.empty();
    }
}
