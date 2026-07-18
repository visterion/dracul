package de.visterion.dracul.hunting.news;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsCredibilityScorerTest {

    private static NewsCredibilityProperties.SourceEntry entry(String match, double score) {
        return new NewsCredibilityProperties.SourceEntry(match, score);
    }

    private static NewsCredibilityScorer scorer(NewsCredibilityProperties.SourceEntry... entries) {
        return new NewsCredibilityScorer(new NewsCredibilityProperties(0.5, 0.3, List.of(entries)));
    }

    @Test void domainHitAloneReturnsDomainScore() {
        assertThat(scorer(entry("reuters.com", 0.9)).score("reuters.com", "unknown-source"))
                .isEqualTo(0.9);
    }

    @Test void sourceHitAloneReturnsSourceScore() {
        assertThat(scorer(entry("reddit-stocks", 0.2)).score("unknownhost.example", "reddit-stocks"))
                .isEqualTo(0.2);
    }

    @Test void bothHitReturnsMinOfHits() {
        // R3 Major 1: a reddit link-post pointing at a wire domain must not inherit
        // wire-grade credibility — min-of-hits keeps the hard-drop guarantee structurally.
        assertThat(scorer(entry("prnewswire.com", 0.4), entry("reddit-stocks", 0.2))
                .score("prnewswire.com", "reddit-stocks")).isEqualTo(0.2);
    }

    @Test void firstHitWinsInConfigOrder() {
        assertThat(scorer(entry("reuters.com", 0.9), entry("reuters.com", 0.1))
                .score("reuters.com", null)).isEqualTo(0.9);
    }

    @Test void matchingIsCaseInsensitive() {
        assertThat(scorer(entry("Reuters.com", 0.9)).score("REUTERS.COM", null)).isEqualTo(0.9);
    }

    @Test void unknownDomainAndSourceReturnDefault() {
        assertThat(scorer(entry("reuters.com", 0.9)).score("other.example", "Other")).isEqualTo(0.5);
    }

    @Test void nullDomainWithKnownSourceUsesSourcePath() {
        assertThat(scorer(entry("reddit-stocks", 0.2)).score(null, "reddit-stocks")).isEqualTo(0.2);
    }

    @Test void bothNullReturnsDefault() {
        assertThat(scorer(entry("reuters.com", 0.9)).score(null, null)).isEqualTo(0.5);
    }

    @Test void blankDomainAndBlankSourceReturnDefault() {
        // R3 Minor 3: the chokepoint's asString("") idiom yields "" — must not key-match.
        assertThat(scorer(entry("reuters.com", 0.9)).score("", "")).isEqualTo(0.5);
    }

    @Test void emptyStringTableMatchNeverMatches() {
        assertThat(scorer(entry("", 0.1)).score("", "")).isEqualTo(0.5);
        assertThat(scorer(entry("", 0.1)).score(null, null)).isEqualTo(0.5);
    }

    @Test void syndicationCornerCaseDomainScoreAppliesAlone() {
        // Spec corner case: source "Reuters" (display name, no source row) but url domain
        // finance.yahoo.com — the domain score 0.6 applies alone; min-of-hits unchanged.
        // Documented as intended: the score reflects where it was published.
        assertThat(scorer(entry("finance.yahoo.com", 0.6)).score("finance.yahoo.com", "Reuters"))
                .isEqualTo(0.6);
    }
}
