package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * D6, part 1: a document Agora REFUSED because it exceeds its filing-size cap is not an outage,
 * and Dracul must stop reading the two as the same thing. Agora opens that error with the stable
 * machine token {@code filing_too_large:}; a genuine outage never carries it.
 *
 * <p>The distinction has two consumers: {@code AgoraClient} must not log a too-large document as
 * "Agora unreachable" (the daily analysis counts that line as an outage), and the merger
 * enrichment must be able to say WHICH kind of degradation cost it the deal terms.
 */
class FilingTextFailureKindTest {

    private static final String URL = "https://www.sec.gov/Archives/edgar/data/1/x.htm";

    @Test void tooLargeIsADistinctFailureKind() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_filing_text"), any())).thenThrow(new AgoraUnavailableException(
                "Agora tool error: filing_too_large: document is 41943040 bytes, cap is 33554432 bytes"));

        FilingText ft = new AgoraFilings(client).filingText(URL);

        assertThat(ft.available()).isFalse();
        assertThat(ft.failure()).isEqualTo(FilingText.Failure.TOO_LARGE);
    }

    @Test void genuineOutageStaysUnavailable() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_filing_text"), any()))
                .thenThrow(new AgoraUnavailableException("Agora unreachable for get_filing_text: connect timed out"));

        FilingText ft = new AgoraFilings(client).filingText(URL);

        assertThat(ft.available()).isFalse();
        assertThat(ft.failure()).isEqualTo(FilingText.Failure.UNAVAILABLE);
    }

    @Test void successCarriesNoFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_filing_text"), any()))
                .thenReturn(new tools.jackson.databind.ObjectMapper().readTree("{\"text\":\"terms\"}"));

        FilingText ft = new AgoraFilings(client).filingText(URL);

        assertThat(ft.available()).isTrue();
        assertThat(ft.failure()).isEqualTo(FilingText.Failure.NONE);
    }

    @Test void blankUrlIsAnUnavailableNotATooLarge() {
        FilingText ft = new AgoraFilings(Mockito.mock(AgoraClient.class)).filingText("  ");
        assertThat(ft.failure()).isEqualTo(FilingText.Failure.UNAVAILABLE);
    }

    // --- the shared predicate both the client logger and the facade branch on -----------------

    @Test void exceptionPredicateRecognisesTheStableToken() {
        assertThat(new AgoraUnavailableException(
                "Agora tool error: filing_too_large: document is 41943040 bytes").filingTooLarge()).isTrue();
        assertThat(new AgoraUnavailableException("empty Agora response for get_filing_text")
                .filingTooLarge()).isFalse();
        assertThat(new AgoraUnavailableException(null, null).filingTooLarge()).isFalse();
    }
}
