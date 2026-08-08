package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @Test void agoraOutageBubblesUpAsUnavailable() {
        when(agora.callTool(eq("search_instruments"), any()))
                .thenThrow(new AgoraUnavailableException("agora down", null));

        assertThatThrownBy(() -> service.search("nokia", 10))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
