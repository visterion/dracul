package de.visterion.dracul.marketdata;

import de.visterion.dracul.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InstrumentSearchControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgoraClient agora = mock(AgoraClient.class);
    private final InstrumentSearchService service = new InstrumentSearchService(agora);

    private void agoraReturns(String json) {
        when(agora.callTool(eq("search_instruments"), any())).thenReturn(MAPPER.readTree(json));
    }

    @Test void unwrapsAgorasResultsIntoAPlainList() {
        agoraReturns("""
            {"results":[{"symbol":"SYNA","name":"Synthetic Alpha Oyj","exchange":"NYSE","type":"EQUITY"}]}
            """);

        List<InstrumentSearchHit> hits = service.search("nokia", 10);

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.symbol()).isEqualTo("SYNA");
            assertThat(h.name()).isEqualTo("Synthetic Alpha Oyj");
            assertThat(h.exchange()).isEqualTo("NYSE");
            assertThat(h.type()).isEqualTo("EQUITY");
        });
    }

    @Test void aQueryShorterThanTwoCharsNeverReachesAgora() {
        assertThat(service.search("n", 10)).isEmpty();
        assertThat(service.search("", 10)).isEmpty();
        assertThat(service.search("  ", 10)).isEmpty();
        assertThat(service.search(null, 10)).isEmpty();

        verify(agora, never()).callTool(any(), any());
    }

    @Test void aTwoCharQueryDoesReachAgora() {
        agoraReturns("{\"results\":[]}");

        service.search("no", 10);

        verify(agora).callTool(eq("search_instruments"), any());
    }

    @Test void limitIsCappedAtTwentyFiveBeforeItLeavesDracul() {
        agoraReturns("{\"results\":[]}");

        service.search("nokia", 999);

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        verify(agora).callTool(eq("search_instruments"), args.capture());
        assertThat(args.getValue().path("limit").asInt()).isEqualTo(25);
    }

    @Test void emptyResultsStayAnEmptyList() {
        agoraReturns("{\"results\":[]}");

        assertThat(service.search("nothingatall", 10)).isEmpty();
    }

    @Test void agoraOutageBubblesUpAsMarketDataUnavailable() {
        when(agora.callTool(eq("search_instruments"), any()))
                .thenThrow(new AgoraUnavailableException("agora down", null));

        assertThatThrownBy(() -> service.search("nokia", 10))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> assertThat(((MarketDataException) e).kind())
                        .isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void aRowMissingSymbolIsSkippedButGoodRowsSurvive() {
        agoraReturns("""
            {"results":[{"symbol":"SYNA","name":"Synthetic Alpha Oyj","exchange":"NYSE","type":"EQUITY"},
                        {"name":"No Symbol Oyj","exchange":"HEL","type":"EQUITY"}]}
            """);

        List<InstrumentSearchHit> hits = service.search("synthetic", 10);

        assertThat(hits).singleElement().satisfies(h -> assertThat(h.symbol()).isEqualTo("SYNA"));
    }

    /**
     * Reaches the HTTP layer: real controller + real GlobalExceptionHandler wired via
     * standalone MockMvc (the pattern used by DecisionDocControllerTest), mocked AgoraClient
     * underneath. Proves the status code, not just the exception type — GlobalExceptionHandler
     * has no handler for AgoraUnavailableException itself, only for MarketDataException, so this
     * would 500 rather than 502 if InstrumentSearchService stopped translating the exception.
     */
    @Test void agoraOutageAnswersHttp502() throws Exception {
        when(agora.callTool(eq("search_instruments"), any()))
                .thenThrow(new AgoraUnavailableException("agora down", null));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new InstrumentSearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/instruments/search").param("q", "nokia"))
                .andExpect(status().isBadGateway());
    }
}
