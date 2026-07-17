package de.visterion.dracul.hunting.news;

import de.visterion.dracul.hunting.agora.NewsHeadline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NewsEventTaggerTest {

    private final NewsEventTagger tagger = new NewsEventTagger();

    private static NewsHeadline news(String headline, String summary) {
        return new NewsHeadline(headline, summary, "src", "news",
                Instant.parse("2026-07-01T12:00:00Z"), "http://n");
    }

    private Set<NewsEventType> tag(String headline, String summary) {
        return tagger.tag(news(headline, summary));
    }

    // --- one match + one non-match per type (keywords are spec §3, 1:1 from ConfounderScreen) ---

    @Test void tagsMa() {
        assertThat(tag("Acme agrees to merger with MegaCorp", "")).containsExactly(NewsEventType.MA);
        assertThat(tag("Acme announces takeover defense", "")).containsExactly(NewsEventType.MA);
        assertThat(tag("Acme opens new factory", "")).isEmpty();
    }

    @Test void maAcquireKeywordNeedsTrailingSpaceOrSuffix() {
        // "acquire " (with trailing space) matches mid-sentence …
        assertThat(tag("Acme will acquire BetaCorp", "")).containsExactly(NewsEventType.MA);
        // … and "acquires" matches via its own keyword.
        assertThat(tag("Acme acquires BetaCorp", "")).containsExactly(NewsEventType.MA);
    }

    @Test void maAcquireAtEndOfHeadlineMatchesViaJoiningSpace() {
        // The pinned match text is headline + " " + summary: the joining space makes
        // "acquire " match even when the headline ENDS with "acquire" and does NOT contain
        // "to acquire" (spec §4.1 / R1-m3) — isolates the joining-space path from the
        // "to acquire" keyword, which would otherwise also match and mask the case.
        assertThat(tag("Which rivals will Acme acquire", "")).containsExactly(NewsEventType.MA);
    }

    @Test void tagsRestatement() {
        assertThat(tag("Acme to restate FY25 results", "")).containsExactly(NewsEventType.RESTATEMENT);
        assertThat(tag("Acme reports solid quarter", "")).isEmpty();
    }

    @Test void tagsGuidanceCut() {
        assertThat(tag("Acme cuts guidance for FY26", "")).containsExactly(NewsEventType.GUIDANCE_CUT);
        assertThat(tag("Acme slashes forecast", "")).containsExactly(NewsEventType.GUIDANCE_CUT);
        assertThat(tag("Acme raises guidance", "")).isEmpty();
    }

    @Test void tagsDilution() {
        assertThat(tag("Acme announces public offering", "")).containsExactly(NewsEventType.DILUTION);
        assertThat(tag("Acme prices secondary offering", "")).containsExactly(NewsEventType.DILUTION);
        assertThat(tag("Acme buys back shares", "")).isEmpty();
    }

    @Test void tagsInvestigation() {
        assertThat(tag("SEC investigation into Acme widens", "")).containsExactly(NewsEventType.INVESTIGATION);
        assertThat(tag("Acme accused of fraud", "")).containsExactly(NewsEventType.INVESTIGATION);
        assertThat(tag("Acme settles routine lawsuit", "")).isEmpty();
    }

    @Test void tagsEarningsMiss() {
        assertThat(tag("Acme misses estimates", "")).containsExactly(NewsEventType.EARNINGS_MISS);
        assertThat(tag("Acme issues profit warning", "")).containsExactly(NewsEventType.EARNINGS_MISS);
        assertThat(tag("Acme beats estimates", "")).isEmpty();
    }

    @Test void tagsMacro() {
        assertThat(tag("Fed raises rates again", "")).containsExactly(NewsEventType.MACRO);
        assertThat(tag("New tariffs hit importers", "")).containsExactly(NewsEventType.MACRO);
        assertThat(tag("Acme launches product", "")).isEmpty();
    }

    // --- multi-tag, ordering, case, robustness ---

    @Test void multipleTagsOnOneHeadlineAreInEnumDeclarationOrder() {
        // Text mentions DILUTION keywords first, MA keyword later — within ONE headline the
        // keyword-hit order is enum declaration order (MA scanned before DILUTION).
        assertThat(tag("Acme announces public offering amid takeover talk", ""))
                .containsExactly(NewsEventType.MA, NewsEventType.DILUTION);
    }

    @Test void summaryIsScannedToo() {
        assertThat(tag("Quarterly report", "company will restate prior results"))
                .containsExactly(NewsEventType.RESTATEMENT);
    }

    @Test void matchingIsCaseInsensitive() {
        assertThat(tag("ACME AGREES TO MERGER", "")).containsExactly(NewsEventType.MA);
        assertThat(tag("acme Cuts Guidance", "")).containsExactly(NewsEventType.GUIDANCE_CUT);
    }

    @Test void nullFieldsNeverThrow() {
        assertThat(tagger.tag(new NewsHeadline(null, null, null, null, null, null))).isEmpty();
        assertThat(tag(null, "company will restate prior results"))
                .containsExactly(NewsEventType.RESTATEMENT);
        assertThat(tag("Acme cuts guidance", null)).containsExactly(NewsEventType.GUIDANCE_CUT);
    }

    @Test void nullHeadlineObjectNeverThrows() {
        assertThat(tagger.tag(null)).isEmpty();
    }

    // --- label()/wireValue()/blocksEcho() mapping, complete (spec §3) ---

    @Test void labelMappingIsCompleteAndCharForChar() {
        Map<NewsEventType, String> expected = Map.of(
                NewsEventType.MA, "m&a",
                NewsEventType.RESTATEMENT, "restatement",
                NewsEventType.GUIDANCE_CUT, "guidance-cut",
                NewsEventType.DILUTION, "dilution",
                NewsEventType.INVESTIGATION, "investigation",
                NewsEventType.EARNINGS_MISS, "earnings-miss",
                NewsEventType.MACRO, "macro");
        for (NewsEventType t : NewsEventType.values()) {
            assertThat(t.label()).isEqualTo(expected.get(t));
        }
        assertThat(NewsEventType.values()).hasSize(7);
    }

    @Test void wireValueMappingIsCompleteLowercaseSnake() {
        Map<NewsEventType, String> expected = Map.of(
                NewsEventType.MA, "ma",
                NewsEventType.RESTATEMENT, "restatement",
                NewsEventType.GUIDANCE_CUT, "guidance_cut",
                NewsEventType.DILUTION, "dilution",
                NewsEventType.INVESTIGATION, "investigation",
                NewsEventType.EARNINGS_MISS, "earnings_miss",
                NewsEventType.MACRO, "macro");
        for (NewsEventType t : NewsEventType.values()) {
            assertThat(t.wireValue()).isEqualTo(expected.get(t));
        }
    }

    @Test void blocksEchoIsTrueForExactlyTheFiveBlockTypes() {
        assertThat(List.of(NewsEventType.values()).stream()
                .filter(NewsEventType::blocksEcho))
                .containsExactly(NewsEventType.MA, NewsEventType.RESTATEMENT,
                        NewsEventType.GUIDANCE_CUT, NewsEventType.DILUTION,
                        NewsEventType.INVESTIGATION);
    }

    @Test void fromWireRoundTripsAndRejectsUnknown() {
        for (NewsEventType t : NewsEventType.values()) {
            assertThat(NewsEventType.fromWire(t.wireValue())).contains(t);
        }
        assertThat(NewsEventType.fromWire("other")).isEmpty();
        assertThat(NewsEventType.fromWire("none")).isEmpty();
        assertThat(NewsEventType.fromWire(null)).isEmpty();
    }
}
