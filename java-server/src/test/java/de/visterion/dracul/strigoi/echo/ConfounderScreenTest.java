package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfounderScreenTest {

    private static NewsHeadline news(String headline, String summary) {
        return new NewsHeadline(headline, summary, "src", "news",
                Instant.parse("2026-06-30T12:00:00Z"), "http://n");
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

    @Test void earningsMissAndMacroHeadlinesProduceNoFlag() {
        // Daywalker-only types (spec §3): EARNINGS_MISS/MACRO must NOT block Echo.
        var screen = new ConfounderScreen(companyData(List.of(
                news("Acme misses estimates", "profit warning issued"),
                news("Fed raises rates", "tariffs and recession fears weigh"))));
        assertThat(screen.confounders("ACME", LocalDate.now().minusDays(5))).isEmpty();
    }

    @Test void flagsAreInHeadlineEncounterOrderNotEnumOrder() {
        // DILUTION is declared AFTER MA in the enum, but appears in the EARLIER headline —
        // encounter order (headline order) must win (spec §4.1/§4.4, R2-M2). This ordered
        // list is persisted via the Index path, so this test pins persisted behavior.
        var screen = new ConfounderScreen(companyData(List.of(
                news("Acme announces secondary offering", ""),
                news("Acme agrees to merger with MegaCorp", ""))));
        assertThat(screen.confounders("ACME", LocalDate.now().minusDays(5)))
                .containsExactly("dilution", "m&a");
    }

    // --- T1.5: pure overload over an already-fetched headline list (spec §5.3/§7) ---

    @Test void pureOverloadScansAGivenHeadlineListWithoutFetching() {
        AgoraCompanyData d = mock(AgoraCompanyData.class); // deliberately unstubbed for .news()
        var screen = new ConfounderScreen(d);

        var flags = screen.confounders(List.of(news("Acme agrees to merger with MegaCorp", "")));

        assertThat(flags).containsExactly("m&a");
        verifyNoInteractions(d);
    }

    @Test void pureOverloadOnEmptyListYieldsEmptyFlags() {
        var screen = new ConfounderScreen(mock(AgoraCompanyData.class));
        assertThat(screen.confounders(List.<NewsHeadline>of())).isEmpty();
    }
}
