package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgoraFilingsFilingTextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsAvailableTextOnSuccess() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        ObjectNode res = mapper.createObjectNode();
        res.put("text", "SUMMARY TERM SHEET offer $52.00 cash").put("section_found", true);
        when(client.callTool(eq("get_filing_text"), any())).thenReturn(res);

        FilingText ft = new AgoraFilings(client).filingText("https://www.sec.gov/Archives/edgar/data/1/x.htm");

        assertThat(ft.available()).isTrue();
        assertThat(ft.text()).contains("$52.00 cash");
    }

    @Test void unavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_filing_text"), any()))
                .thenThrow(new AgoraUnavailableException("down"));

        FilingText ft = new AgoraFilings(client).filingText("https://www.sec.gov/Archives/edgar/data/1/x.htm");

        assertThat(ft.available()).isFalse();
        assertThat(ft.text()).isEmpty();
    }

    @Test void blankUrlSkipsCallAndIsUnavailable() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        FilingText ft = new AgoraFilings(client).filingText("  ");
        assertThat(ft.available()).isFalse();
        verify(client, never()).callTool(any(), any());
    }
}
