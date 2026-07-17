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
 */
@Component
public class ConfounderScreen {

    private final AgoraCompanyData companyData;
    private final NewsEventTagger tagger = new NewsEventTagger();

    public ConfounderScreen(AgoraCompanyData companyData) { this.companyData = companyData; }

    public List<String> confounders(String symbol, LocalDate since) {
        Set<String> flags = new LinkedHashSet<>();
        for (NewsHeadline h : companyData.news(symbol, since, LocalDate.now())) {
            for (NewsEventType t : tagger.tag(h)) {
                if (t.blocksEcho()) flags.add(t.label());
            }
        }
        return new ArrayList<>(flags);
    }
}
