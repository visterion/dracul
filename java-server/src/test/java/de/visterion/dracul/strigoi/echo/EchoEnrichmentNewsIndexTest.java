package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.NewsHeadline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T-echo-index: der Kandidaten-Payload trägt einen gekappten News-INDEX ohne summary,
 *  aber die ungekappte Gesamtzahl in newsCount. Alle Fixtures sind synthetisch. */
class EchoEnrichmentNewsIndexTest {

    private static NewsHeadline synthetic(int i) {
        return new NewsHeadline(
                "SYNTHETIC headline " + i,
                "SYNTHETIC summary body for item " + i,
                "synthetic-source", "rss",
                Instant.parse("2026-01-0" + (i + 1) + "T00:00:00Z"),
                "https://example.com/" + i, "example.com", 0.6);
    }

    @Test
    void indexItemCarriesNoSummary() {
        var item = new EchoNewsIndexItem("SYNTHETIC headline", "synthetic-source", 0.6,
                Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(item.headline()).isEqualTo("SYNTHETIC headline");
        assertThat(item.credibility()).isEqualTo(0.6);
        // Das Record hat bewusst KEINE summary-Komponente — das ist der ganze Punkt.
        assertThat(EchoNewsIndexItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("headline", "source", "credibility", "datetime");
    }

    @Test
    void candidateCarriesUncappedNewsCountAlongsideCappedIndex() {
        List<EchoNewsIndexItem> index = List.of(
                new EchoNewsIndexItem("SYNTHETIC 1", "synthetic-source", 0.6,
                        Instant.parse("2026-01-01T00:00:00Z")));

        var candidate = new EnrichedPeadCandidate(
                "SYNTH1", "Synthetic Corp", java.time.LocalDate.parse("2026-01-01"), 2,
                null, null, null, null, null, false, false,
                null, false, null, null,
                null, null, false, null, null, null,
                null, null, null, false,
                null, false, null, null, false,
                null, null, null, false,
                index, 12);

        assertThat(candidate.recentNews()).hasSize(1);
        assertThat(candidate.newsCount()).isEqualTo(12);
    }

    @Test
    void confounderBeyondTheIndexCapStillBlocks() {
        // 8 Items; NUR das sechste (Index 5, jenseits des Caps von 5) trägt den Confounder,
        // und zwar ausschließlich im summary — nicht in der headline.
        List<NewsHeadline> news = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            news.add(i == 5
                    ? new NewsHeadline(
                            "SYNTHETIC neutral headline 5",
                            "SYNTHETIC body mentioning a merger agreement",
                            "synthetic-source", "rss",
                            Instant.parse("2026-01-06T00:00:00Z"),
                            "https://example.com/5", "example.com", 0.6)
                    : synthetic(i));
        }

        var screen = new ConfounderScreen(null); // die pure Überladung macht keinen Agora-Call

        assertThat(screen.confounders(news))
                .as("der Confounder-Gate scannt die VOLLE Liste, unabhängig vom Index-Cap")
                .containsExactly("m&a");
    }
}
