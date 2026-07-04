package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraMarketDataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void resolveBuildsMarketDataFromQuoteAndOhlc() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_quote"), any())).thenReturn(json(
                "{\"quotes\":[{\"symbol\":\"AAPL\",\"price\":190.5,\"dayChangePercent\":1.25,\"currency\":\"USD\"}]}"));
        when(client.callTool(eq("get_ohlc"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"bars\":[" +
                "{\"date\":\"2025-01-02\",\"open\":10,\"high\":11,\"low\":9,\"close\":10.5,\"volume\":1000}," +
                "{\"date\":\"2025-01-03\",\"open\":10.5,\"high\":12,\"low\":10,\"close\":11.2,\"volume\":2000}]}"));
        AgoraMarketData md = new AgoraMarketData(client);

        MarketData r = md.resolve("AAPL");
        assertThat(r.currentPrice()).isEqualByComparingTo("190.5");
        assertThat(r.dayChangePercent()).isEqualByComparingTo("1.25");
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.priceHistory30d()).containsExactly(new java.math.BigDecimal("10.5"), new java.math.BigDecimal("11.2"));
    }

    @Test void quotesParsesArrayToMapAndOmitsMissing() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_quote"), any())).thenReturn(json(
                "{\"quotes\":[{\"symbol\":\"AAPL\",\"price\":190.5,\"dayChangePercent\":1.25}]}"));
        AgoraMarketData md = new AgoraMarketData(client);

        Map<String, Quote> q = md.quotes(List.of("AAPL", "MSFT"));
        assertThat(q).containsOnlyKeys("AAPL");
        assertThat(q.get("AAPL").price()).isEqualByComparingTo("190.5");
    }

    @Test void quotesReturnsEmptyMapOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_quote"), any())).thenThrow(new AgoraUnavailableException("down"));
        AgoraMarketData md = new AgoraMarketData(client);
        assertThat(md.quotes(List.of("AAPL"))).isEmpty();
    }

    @Test void dailyOhlcHistoryParsesBars() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_ohlc"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"bars\":[{\"date\":\"2025-01-02\",\"open\":10,\"high\":11,\"low\":9,\"close\":10.5,\"volume\":1000}]}"));
        AgoraMarketData md = new AgoraMarketData(client);
        List<OhlcBar> bars = md.dailyOhlcHistory("AAPL", 260);
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).date()).isEqualTo(java.time.LocalDate.parse("2025-01-02"));
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(0).volume()).isEqualTo(1000L);
    }

    @Test void dailyOhlcHistoryThrowsMarketDataExceptionOnFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_ohlc"), any())).thenThrow(new AgoraUnavailableException("down"));
        AgoraMarketData md = new AgoraMarketData(client);
        assertThatThrownBy(() -> md.dailyOhlcHistory("AAPL", 260))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void resolveThrowsWhenQuoteMissing() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_quote"), any())).thenReturn(json("{\"quotes\":[]}"));
        AgoraMarketData md = new AgoraMarketData(client);
        assertThatThrownBy(() -> md.resolve("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
