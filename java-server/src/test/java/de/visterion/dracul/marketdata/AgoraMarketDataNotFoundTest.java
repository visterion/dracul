package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 8, Step 2: regression guard for the claim the whole 502->422 fix rests on. Agora already
 * answers an unknown symbol as a successful envelope carrying {@code available:false} and an
 * empty {@code quotes[]} (measured on prod 2026-08-08 for {@code NOKIA}), so Dracul's existing
 * code already turns that into 422 without any production change here: {@link AgoraClient#parseToolText}
 * branches only on {@code isError}, {@link AgoraMarketData#resolve} throws
 * {@link MarketDataException.Kind#NOT_FOUND} on an empty {@code quotes} array, and
 * {@code GlobalExceptionHandler} maps {@code NOT_FOUND} to 422. This test is expected to pass
 * unmodified — if it does not, the design's central assumption is wrong.
 */
class AgoraMarketDataNotFoundTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Agora's noData shape: available=false INSIDE a successful envelope. */
    private static final String NO_DATA = """
        {"quotes":[],"unresolved":["NOKIA"],"available":false,"error":"no quote for NOKIA"}
        """;

    @Test void aNoDataPayloadIsReturnedAsIsInsteadOfThrowing() {
        assertThatCode(() -> AgoraClient.parseToolText(NO_DATA, false)).doesNotThrowAnyException();
        assertThat(AgoraClient.parseToolText(NO_DATA, false).path("quotes")).isEmpty();
    }

    @Test void anErrorEnvelopeStillThrows() {
        assertThatThrownBy(() -> AgoraClient.parseToolText(NO_DATA, true))
                .isInstanceOf(AgoraUnavailableException.class);
    }

    @Test void resolveTurnsAnEmptyQuotesArrayIntoNotFound() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(any(), any())).thenReturn(MAPPER.readTree(NO_DATA));

        assertThatThrownBy(() -> new AgoraMarketData(agora).resolve("NOKIA"))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> assertThat(((MarketDataException) e).kind())
                        .isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
