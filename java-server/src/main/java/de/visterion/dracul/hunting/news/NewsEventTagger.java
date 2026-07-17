package de.visterion.dracul.hunting.news;

import de.visterion.dracul.hunting.agora.NewsHeadline;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic keyword tagger over one news headline (T1.3). Match text is pinned to
 * {@code headline + " " + summary}, lowercased — exactly the historical ConfounderScreen
 * semantics; the joining space is load-bearing for the {@code "acquire "} keyword at
 * headline end. Plain stateless class, intentionally NOT a Spring bean: consumers
 * (ConfounderScreen, NewsDetector) instantiate it as a field. Never throws; the returned
 * set iterates in keyword-hit encounter order (= enum declaration order within one
 * headline) — callers accumulating across headlines preserve headline order themselves.
 */
public final class NewsEventTagger {

    /** Deterministic; never throws; empty set when nothing matches. */
    public Set<NewsEventType> tag(NewsHeadline h) {
        Set<NewsEventType> tags = new LinkedHashSet<>();
        if (h == null) return tags;
        String text = ((h.headline() == null ? "" : h.headline()) + " "
                + (h.summary() == null ? "" : h.summary())).toLowerCase(Locale.ROOT);
        for (NewsEventType t : NewsEventType.values()) {
            for (String kw : t.keywords()) {
                if (text.contains(kw)) { tags.add(t); break; }
            }
        }
        return tags;
    }
}
