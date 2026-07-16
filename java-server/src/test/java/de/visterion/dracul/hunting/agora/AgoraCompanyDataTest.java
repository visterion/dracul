package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraCompanyDataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void newsMapsRowsParsesInstantAndSkipsBlankHeadline() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Apple ships thing\",\"summary\":\"A summary.\",\"source\":\"WSJ\"," +
                "\"datetime\":\"2026-06-30T14:05:00Z\",\"url\":\"http://n/1\"}," +
                "{\"headline\":\"\",\"summary\":\"no headline\",\"source\":\"X\"," +
                "\"datetime\":\"2026-06-30T15:00:00Z\",\"url\":\"http://n/2\"}]}"));
        AgoraCompanyData data = new AgoraCompanyData(client);

        List<NewsHeadline> out = data.news("AAPL", LocalDate.parse("2026-06-28"), LocalDate.parse("2026-07-01"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).headline()).isEqualTo("Apple ships thing");
        assertThat(out.get(0).summary()).isEqualTo("A summary.");
        assertThat(out.get(0).source()).isEqualTo("WSJ");
        assertThat(out.get(0).datetime()).isEqualTo(Instant.parse("2026-06-30T14:05:00Z"));
        assertThat(out.get(0).url()).isEqualTo("http://n/1");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_company_news"), args.capture());
        assertThat(args.getValue().path("symbol").asString()).isEqualTo("AAPL");
        assertThat(args.getValue().path("from").asString()).isEqualTo("2026-06-28");
        assertThat(args.getValue().path("to").asString()).isEqualTo("2026-07-01");
    }

    @Test void newsReturnsEmptyListOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(new AgoraCompanyData(client).news("AAPL", LocalDate.now().minusDays(1), LocalDate.now()))
                .isEmpty();
    }

    @Test void newsDefaultsSourceTypeToNewsWhenFieldMissing() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Old Agora item without sourceType\",\"summary\":\"s\",\"source\":\"WSJ\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"http://n/1\"}," +
                "{\"headline\":\"New Agora item with sourceType\",\"summary\":\"s\",\"source\":\"yahoo-rss\"," +
                "\"sourceType\":\"news\",\"datetime\":\"2026-07-15T11:00:00Z\",\"url\":\"http://n/2\"}]}"));
        AgoraCompanyData data = new AgoraCompanyData(client);

        List<NewsHeadline> out = data.news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(2);
        assertThat(out.get(0).sourceType()).isEqualTo("news"); // missing field defaults to "news"
        assertThat(out.get(1).sourceType()).isEqualTo("news"); // explicit field passed through
    }

    @Test void recommendationsMapsCounts() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_analyst_estimates"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"recommendations\":[" +
                "{\"period\":\"2026-06-01\",\"strongBuy\":10,\"buy\":5,\"hold\":3,\"sell\":1,\"strongSell\":0}," +
                "{\"period\":\"2026-05-01\",\"strongBuy\":8,\"buy\":5,\"hold\":4,\"sell\":2,\"strongSell\":1}]}"));
        AgoraCompanyData data = new AgoraCompanyData(client);

        List<RecommendationTrend> out = data.recommendations("AAPL");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).period()).isEqualTo("2026-06-01");
        assertThat(out.get(0).strongBuy()).isEqualTo(10);
        assertThat(out.get(0).buy()).isEqualTo(5);
        assertThat(out.get(0).hold()).isEqualTo(3);
        assertThat(out.get(0).sell()).isEqualTo(1);
        assertThat(out.get(0).strongSell()).isEqualTo(0);
    }

    @Test void recommendationsReturnsEmptyListOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_analyst_estimates"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(new AgoraCompanyData(client).recommendations("AAPL")).isEmpty();
    }

    @Test void fundamentalsReturnsRawMetricsBlob() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamentals"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"metrics\":{\"beta\":1.2,\"52WeekLow\":150.0,\"roaTTM\":21.5}}"));
        JsonNode m = new AgoraCompanyData(client).fundamentals("AAPL");
        assertThat(m).isNotNull();
        assertThat(m.path("beta").asDouble()).isEqualTo(1.2);
        assertThat(m.path("52WeekLow").asDouble()).isEqualTo(150.0);
    }

    @Test void fundamentalsReturnsNullOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamentals"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(new AgoraCompanyData(client).fundamentals("AAPL")).isNull();
    }

    @Test void fundamentalsReturnsNullWhenMetricsMissingOrNull() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamentals"), any())).thenReturn(json("{\"symbol\":\"AAPL\",\"metrics\":null}"));
        assertThat(new AgoraCompanyData(client).fundamentals("AAPL")).isNull();
    }

    @Test void profileReturnsRawBlobAndNullOnFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_profile"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"profile\":{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\"}}"));
        JsonNode p = new AgoraCompanyData(client).profile("AAPL");
        assertThat(p).isNotNull();
        assertThat(p.path("finnhubIndustry").asString()).isEqualTo("Technology");

        AgoraClient down = Mockito.mock(AgoraClient.class);
        when(down.callTool(eq("get_company_profile"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(new AgoraCompanyData(down).profile("AAPL")).isNull();
    }
}
