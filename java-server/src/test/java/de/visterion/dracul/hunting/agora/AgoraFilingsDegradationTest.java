package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * D4: the three MARKET-WIDE filing fetches must (a) ask Agora for the tool maximum instead of
 * silently taking its 100-row default, and (b) read {@code partial}/{@code truncated} back and
 * degrade health while KEEPING the items — exactly what {@link AgoraEarnings#recent} does.
 *
 * <p>Before this, {@code recentForm4} / {@code searchSpinoffs} / {@code searchMergers} all ended
 * with an unconditional {@code DataSourceResult.healthy(...)}, so a market-wide Form-4 scan cut at
 * 100 of several thousand filings reported {@code partial=false truncated=false status=healthy}
 * and a 20-day and a 90-day merger window returned an identical 25 candidates.
 */
class AgoraFilingsDegradationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    private static final LocalDate FROM = LocalDate.parse("2026-07-01");
    private static final LocalDate TO = LocalDate.parse("2026-08-01");

    /** The tool maximum of get_form4_transactions / search_filings (Agora MAX_LIMIT). */
    private static final int TOOL_MAX = 1000;

    // --- (a) explicit limit on every market-wide call -----------------------------------------

    @Test void recentForm4AsksForTheToolMaximum() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any()))
                .thenReturn(json("{\"transactions\":[]}"));

        new AgoraFilings(client).recentForm4(FROM, TO);

        // one call per day since the day-slicing (BUG-S1b); every one of them asks for the maximum
        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client, Mockito.atLeastOnce()).callTool(eq("get_form4_transactions"), args.capture());
        assertThat(args.getAllValues()).isNotEmpty()
                .allSatisfy(a -> assertThat(a.path("limit").asInt(0)).isEqualTo(TOOL_MAX));
    }

    @Test void searchSpinoffsAsksForTheToolMaximum() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any())).thenReturn(json("{\"filings\":[]}"));

        new AgoraFilings(client).searchSpinoffs(FROM, TO);

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("search_filings"), args.capture());
        assertThat(args.getValue().path("limit").asInt(0)).isEqualTo(TOOL_MAX);
    }

    @Test void searchMergersAsksForTheToolMaximum() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any())).thenReturn(json("{\"filings\":[]}"));

        new AgoraFilings(client).searchMergers(FROM, TO);

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("search_filings"), args.capture());
        assertThat(args.getValue().path("limit").asInt(0)).isEqualTo(TOOL_MAX);
    }

    // --- (b) truncated / partial are read back and degrade health, items kept -----------------

    @Test void recentForm4ReportsTruncationAndKeepsItems() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenReturn(json(
                "{\"truncated\":true,\"transactions\":[{\"ticker\":\"ACME\",\"filerName\":\"A\","
                        + "\"filerRole\":\"CFO\",\"transactionDate\":\"2026-07-15\",\"shares\":10,"
                        + "\"dollarValue\":100,\"code\":\"P\"}]}"));

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO);

        assertThat(r.items()).hasSize(1);                 // items kept, never dropped
        assertThat(r.health().isHealthy()).isTrue();      // degraded is still "healthy" by design
        assertThat(r.health().truncated()).isTrue();
        assertThat(r.health().partial()).isFalse();
        assertThat(r.health().detail()).contains("truncated");
    }

    @Test void recentForm4ReportsPartialCoverage() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any()))
                .thenReturn(json("{\"partial\":true,\"transactions\":[]}"));

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO);

        assertThat(r.health().partial()).isTrue();
        assertThat(r.health().detail()).contains("partial");
    }

    @Test void searchSpinoffsReportsTruncationAndKeepsItems() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any())).thenReturn(json(
                "{\"truncated\":true,\"filings\":[{\"ticker\":\"SPN\",\"company\":\"Spinco\","
                        + "\"form\":\"10-12B\",\"filedDate\":\"2026-07-20\",\"url\":\"http://sec/u\"}]}"));

        DataSourceResult<SpinoffFiling> r = new AgoraFilings(client).searchSpinoffs(FROM, TO);

        assertThat(r.items()).hasSize(1);
        assertThat(r.health().truncated()).isTrue();
        assertThat(r.health().isHealthy()).isTrue();
    }

    @Test void searchMergersReportsTruncationAndKeepsItems() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any())).thenReturn(json(
                "{\"truncated\":true,\"filings\":[{\"ticker\":\"TGT\",\"company\":\"Target\","
                        + "\"form\":\"DEFM14A\",\"filedDate\":\"2026-07-20\",\"url\":\"http://sec/u\"}]}"));

        DataSourceResult<MergerFiling> r = new AgoraFilings(client).searchMergers(FROM, TO);

        assertThat(r.items()).hasSize(1);
        assertThat(r.health().truncated()).isTrue();
        assertThat(r.health().isHealthy()).isTrue();
    }

    @Test void cleanResponseStaysPlainHealthy() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any()))
                .thenReturn(json("{\"truncated\":false,\"filings\":[]}"));

        DataSourceResult<MergerFiling> r = new AgoraFilings(client).searchMergers(FROM, TO);

        assertThat(r.health().truncated()).isFalse();
        assertThat(r.health().partial()).isFalse();
        assertThat(r.health().detail()).isNull();
    }
}
