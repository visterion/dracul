package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepotInstrumentServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    private AgoraClient allSucceedingAgora() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(json("{\"symbol\":\"ACME\",\"name\":\"Acme Corp\"}"));
        when(agora.callTool(eq("get_company_news"), any())).thenReturn(json("{\"symbol\":\"ACME\",\"news\":[]}"));
        when(agora.callTool(eq("get_earnings_window"), any())).thenReturn(json("{\"events\":[]}"));
        when(agora.callTool(eq("get_analyst_estimates"), any())).thenReturn(json("{\"symbol\":\"ACME\",\"recommendations\":[]}"));
        when(agora.callTool(eq("get_earnings_estimates"), any())).thenReturn(json("{\"symbol\":\"ACME\",\"estimates\":[]}"));
        when(agora.callTool(eq("get_fundamental_score"), any())).thenReturn(json("{\"symbol\":\"ACME\",\"score\":7}"));
        when(agora.callTool(eq("get_fundamentals"), any())).thenReturn(json("{\"symbol\":\"ACME\",\"peRatio\":20}"));
        when(agora.callTool(eq("get_form4_transactions"), any())).thenReturn(json("{\"transactions\":[]}"));
        return agora;
    }

    @Test
    void allEightToolsSucceedAllSectionsPresent() {
        AgoraClient agora = allSucceedingAgora();
        DepotInstrumentService service = new DepotInstrumentService(agora);

        var bundle = service.bundle("ACME");

        assertThat(bundle.symbol()).isEqualTo("ACME");
        assertThat(bundle.profile()).isNotNull();
        assertThat(bundle.news()).isNotNull();
        assertThat(bundle.earnings()).isNotNull();
        assertThat(bundle.analystEstimates()).isNotNull();
        assertThat(bundle.earningsEstimates()).isNotNull();
        assertThat(bundle.fundamentalScore()).isNotNull();
        assertThat(bundle.fundamentals()).isNotNull();
        assertThat(bundle.insiderActivity()).isNotNull();

        assertThat(bundle.profile().path("name").asString()).isEqualTo("Acme Corp");
    }

    @Test
    void oneToolThrowingLeavesThatSectionNullRestPresent() {
        AgoraClient agora = allSucceedingAgora();
        when(agora.callTool(eq("get_analyst_estimates"), any()))
                .thenThrow(new AgoraUnavailableException("agora down"));
        DepotInstrumentService service = new DepotInstrumentService(agora);

        var bundle = service.bundle("ACME");

        assertThat(bundle.analystEstimates()).isNull();
        assertThat(bundle.profile()).isNotNull();
        assertThat(bundle.news()).isNotNull();
        assertThat(bundle.earnings()).isNotNull();
        assertThat(bundle.earningsEstimates()).isNotNull();
        assertThat(bundle.fundamentalScore()).isNotNull();
        assertThat(bundle.fundamentals()).isNotNull();
        assertThat(bundle.insiderActivity()).isNotNull();
    }

    @Test
    void allEightToolsThrowingYieldsAllNullSectionsShapeIntact() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(any(), any())).thenThrow(new AgoraUnavailableException("agora down"));
        DepotInstrumentService service = new DepotInstrumentService(agora);

        var bundle = service.bundle("ACME");

        assertThat(bundle.symbol()).isEqualTo("ACME");
        assertThat(bundle.profile()).isNull();
        assertThat(bundle.news()).isNull();
        assertThat(bundle.earnings()).isNull();
        assertThat(bundle.analystEstimates()).isNull();
        assertThat(bundle.earningsEstimates()).isNull();
        assertThat(bundle.fundamentalScore()).isNull();
        assertThat(bundle.fundamentals()).isNull();
        assertThat(bundle.insiderActivity()).isNull();
    }

    @Test
    void companyProfileAndNewsCarrySymbolArg() {
        AgoraClient agora = allSucceedingAgora();
        DepotInstrumentService service = new DepotInstrumentService(agora);

        service.bundle("ACME");

        verify(agora).callTool(eq("get_company_profile"),
                org.mockito.ArgumentMatchers.argThat(args -> "ACME".equals(args.path("symbol").asString())));
        verify(agora).callTool(eq("get_company_news"),
                org.mockito.ArgumentMatchers.argThat(args ->
                        "ACME".equals(args.path("symbol").asString())
                                && args.path("from").asString() != null
                                && args.path("to").asString() != null
                                && !args.path("from").asString().isBlank()
                                && !args.path("to").asString().isBlank()));
    }

    @Test
    void earningsWindowAndInsiderActivityHaveNoSymbolArg() {
        AgoraClient agora = allSucceedingAgora();
        DepotInstrumentService service = new DepotInstrumentService(agora);

        service.bundle("ACME");

        verify(agora).callTool(eq("get_earnings_window"),
                org.mockito.ArgumentMatchers.argThat(args -> args.path("symbol").isMissingNode()));
        verify(agora).callTool(eq("get_form4_transactions"),
                org.mockito.ArgumentMatchers.argThat(args -> args.path("symbol").isMissingNode()));
    }
}
