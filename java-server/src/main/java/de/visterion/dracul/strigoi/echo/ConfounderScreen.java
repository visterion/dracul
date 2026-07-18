package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.hunting.news.NewsEventTagger;
import de.visterion.dracul.hunting.news.NewsEventType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Confounder screen over company news since the report date. Delegates keyword detection
 * to the shared {@link NewsEventTagger} (T1.3) and maps ONLY the Echo-blocking types onto
 * their historical flag strings ({@link NewsEventType#label()}), deduplicated in headline
 * encounter order. EARNINGS_MISS/MACRO are Daywalker-only and never flag here. Never
 * throws (the facade never throws); empty = clean.
 *
 * <p>Two entry points (T1.5, spec §5.3): {@link #confounders(String, LocalDate)} is the
 * original fetch-and-scan convenience still used by {@code IndexDemandSnapshotter}, which
 * has no reason to see the raw headlines. {@link #confounders(List)} is a PURE scan over an
 * already-fetched headline list, added so {@link EchoEnrichmentService} can fetch
 * {@link AgoraCompanyData#news} exactly ONCE per candidate and reuse that single fetch for
 * both the confounder flags and the {@code recentNews} surfaced to the Echo LLM — {@code
 * news()} is uncached, so a second call would be a real doubled Agora round-trip.
 */
@Component
public class ConfounderScreen {

    private final AgoraCompanyData companyData;
    private final NewsEventTagger tagger = new NewsEventTagger();

    public ConfounderScreen(AgoraCompanyData companyData) { this.companyData = companyData; }

    /** Fetches company news since {@code since} and scans it (one Agora round-trip). */
    public List<String> confounders(String symbol, LocalDate since) {
        return confounders(companyData.news(symbol, since, LocalDate.now()));
    }

    /** Pure scan over an ALREADY-FETCHED headline list — makes no Agora call itself. */
    public List<String> confounders(List<NewsHeadline> headlines) {
        Set<String> flags = new LinkedHashSet<>();
        for (NewsHeadline h : headlines) {
            for (NewsEventType t : tagger.tag(h)) {
                if (t.blocksEcho()) flags.add(t.label());
            }
        }
        return new ArrayList<>(flags);
    }
}
