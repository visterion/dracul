package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceResult;
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
 * encounter order. EARNINGS_MISS/MACRO are Daywalker-only and never flag here. Never throws.
 *
 * <p>Two entry points (T1.5, spec §5.3), with DIFFERENT source-down handling. {@link
 * #confounders(String, LocalDate)} is the fetch-and-scan convenience used by {@code
 * IndexDemandSnapshotter}: it calls the health-aware {@link AgoraCompanyData#newsResult} and
 * returns a {@link ConfounderProbe} that distinguishes "scanned, found nothing" from "the news
 * source did not answer" — a news outage must never be persisted as the positive statement that
 * no dilution/M&amp;A/restatement/guidance-cut event exists. {@link #confounders(List)} is a PURE
 * scan over an already-fetched headline list and stays a plain {@code List<String>}: it never
 * calls Agora itself, so there is no source-down state for it to carry. It exists so {@link
 * EchoEnrichmentService} can fetch {@link AgoraCompanyData#news} exactly ONCE per candidate and
 * reuse that single fetch for both the confounder flags and the {@code recentNews} surfaced to
 * the Echo LLM — {@code news()} is uncached, so a second call would be a real doubled Agora
 * round-trip.
 */
@Component
public class ConfounderScreen {

    private final AgoraCompanyData companyData;
    private final NewsEventTagger tagger = new NewsEventTagger();

    public ConfounderScreen(AgoraCompanyData companyData) { this.companyData = companyData; }

    /**
     * Fetches company news since {@code since} and scans it (one Agora round-trip). Uses the
     * health-aware {@link AgoraCompanyData#newsResult}: when the source did not answer, this
     * returns {@link ConfounderProbe#sourceDown()} (with {@link ConfounderProbe#unknown()} true)
     * rather than an empty (= "clean") result, because
     * this value is persisted (see {@code IndexDemandSnapshotter}) and an outage read back as
     * "no confounders" would be the exact inversion of the truth.
     */
    public ConfounderProbe confounders(String symbol, LocalDate since) {
        DataSourceResult<NewsHeadline> result = companyData.newsResult(symbol, since, LocalDate.now());
        if (!result.health().isHealthy()) {
            return ConfounderProbe.sourceDown();
        }
        return ConfounderProbe.of(confounders(result.items()));
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
