package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.finnhub.NewsHeadline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfounderScreenTest {

    private static NewsHeadline news(String headline, String summary) {
        return new NewsHeadline(headline, summary, "src", Instant.parse("2026-06-30T12:00:00Z"), "http://n");
    }

    private static AgoraCompanyData companyData(List<NewsHeadline> headlines) {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.news(eq("ACME"), any(LocalDate.class), any(LocalDate.class))).thenReturn(headlines);
        return d;
    }

    @Test void flagsDistinctCategoriesFromHeadlineAndSummary() {
        var screen = new ConfounderScreen(companyData(List.of(
                news("Acme agrees to merger with MegaCorp", ""),
                news("Acme announces takeover defense", ""),                      // same category, deduped
                news("Quarterly report", "company will restate prior results")))); // summary scanned too
        assertThat(screen.confounders("ACME", LocalDate.now().minusDays(5)))
                .containsExactly("m&a", "restatement");
    }

    @Test void cleanNewsYieldsEmptyList() {
        var screen = new ConfounderScreen(companyData(List.of(news("Acme wins award", "nice quarter"))));
        assertThat(screen.confounders("ACME", LocalDate.now().minusDays(5))).isEmpty();
    }

    @Test void noNewsYieldsEmptyList() {
        var screen = new ConfounderScreen(companyData(List.of()));
        assertThat(screen.confounders("ACME", LocalDate.now().minusDays(5))).isEmpty();
    }
}
