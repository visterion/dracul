package de.visterion.dracul.hunting.news;

import org.springframework.stereotype.Component;

/**
 * Pure credibility scorer over the static operator table (T1.4). Looks up
 * {@code domain} and {@code source} independently (each exact, case-insensitive,
 * first hit wins in config order); both hit -> min of the two (a reddit link-post
 * pointing at a wire domain must never inherit wire-grade credibility — R3 Major 1);
 * one hit -> that score; no hit -> {@code defaultScore}. Total function: null and
 * blank keys never match, an empty-string table match never matches.
 */
@Component
public class NewsCredibilityScorer {

    private final NewsCredibilityProperties props;

    public NewsCredibilityScorer(NewsCredibilityProperties props) {
        this.props = props;
    }

    public double score(String domain, String source) {
        Double domainScore = lookup(domain);
        Double sourceScore = lookup(source);
        if (domainScore != null && sourceScore != null) {
            return Math.min(domainScore, sourceScore);
        }
        if (domainScore != null) return domainScore;
        if (sourceScore != null) return sourceScore;
        return props.defaultScore();
    }

    private Double lookup(String key) {
        if (key == null || key.isBlank()) return null;
        for (NewsCredibilityProperties.SourceEntry e : props.sources()) {
            if (e.match() != null && !e.match().isBlank() && e.match().equalsIgnoreCase(key)) {
                return e.score();
            }
        }
        return null;
    }
}
