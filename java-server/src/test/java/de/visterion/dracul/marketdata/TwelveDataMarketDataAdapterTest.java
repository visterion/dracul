package de.visterion.dracul.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class TwelveDataMarketDataAdapterTest {

    static WireMockServer wm;
    TwelveDataMarketDataAdapter adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach void setUp() {
        wm.resetAll();
        adapter = new TwelveDataMarketDataAdapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "testkey", 120);
    }

    @Test void resolveReturnsPriceHistoryAndDayChange() {
        wm.stubFor(get(urlPathEqualTo("/quote")).withQueryParam("symbol", equalTo("AAPL"))
                .willReturn(okJson("""
                    {"symbol":"AAPL","name":"Apple Inc.","close":"190.5","previous_close":"188.0","percent_change":"1.33","status":"ok"}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/time_series")).withQueryParam("symbol", equalTo("AAPL"))
                .willReturn(okJson("""
                    {"values":[{"datetime":"2026-06-11","close":"190.5"},{"datetime":"2026-06-10","close":"188.0"}],"status":"ok"}
                    """)));

        MarketData md = adapter.resolve("AAPL");

        assertThat(md.companyName()).isEqualTo("Apple Inc.");
        assertThat(md.currentPrice()).isEqualByComparingTo("190.5");
        assertThat(md.dayChangePercent()).isEqualByComparingTo("1.33");
        assertThat(md.priceHistory30d()).containsExactly(new BigDecimal("188.0"), new BigDecimal("190.5"));
    }

    @Test void quotesBatchParsesKeyedObject() {
        wm.stubFor(get(urlPathEqualTo("/quote")).withQueryParam("symbol", equalTo("AAPL,MSFT"))
                .willReturn(okJson("""
                    {"AAPL":{"close":"190.5","percent_change":"1.33","status":"ok"},
                     "MSFT":{"close":"402.1","percent_change":"-0.30","status":"ok"}}
                    """)));

        Map<String, Quote> q = adapter.quotes(List.of("AAPL", "MSFT"));

        assertThat(q.get("AAPL").price()).isEqualByComparingTo("190.5");
        assertThat(q.get("AAPL").dayChangePercent()).isEqualByComparingTo("1.33");
        assertThat(q.get("MSFT").dayChangePercent()).isEqualByComparingTo("-0.30");
    }

    @Test void quotesSingleSymbolParsesFlatObject() {
        wm.stubFor(get(urlPathEqualTo("/quote")).withQueryParam("symbol", equalTo("AAPL"))
                .willReturn(okJson("""
                    {"symbol":"AAPL","close":"190.5","percent_change":"1.33","status":"ok"}
                    """)));

        Map<String, Quote> q = adapter.quotes(List.of("AAPL"));

        assertThat(q.get("AAPL").price()).isEqualByComparingTo("190.5");
    }

    @Test void quotesAreCachedWithinTtl() {
        wm.stubFor(get(urlPathEqualTo("/quote"))
                .willReturn(okJson("""
                    {"symbol":"AAPL","close":"190.5","percent_change":"1.33","status":"ok"}
                    """)));

        adapter.quotes(List.of("AAPL"));
        adapter.quotes(List.of("AAPL"));

        wm.verify(1, getRequestedFor(urlPathEqualTo("/quote")));
    }

    @Test void resolveThrowsNotFoundOnErrorStatus() {
        wm.stubFor(get(urlPathEqualTo("/quote")).withQueryParam("symbol", equalTo("ZZZZ"))
                .willReturn(okJson("""
                    {"code":404,"message":"symbol not found","status":"error"}
                    """)));

        assertThatThrownBy(() -> adapter.resolve("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void dailyOhlcHistoryReturnsBarsOldestFirst() {
        // TwelveData API returns newest-first; adapter must reverse to oldest-first
        wm.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withQueryParam("interval", equalTo("1day"))
                .withQueryParam("outputsize", equalTo("260"))
                .willReturn(okJson("""
                    {"values":[
                        {"datetime":"2026-06-13 00:00:00","open":"11.0","high":"11.5","low":"10.8","close":"11.2","volume":"3000"},
                        {"datetime":"2026-06-12 00:00:00","open":"10.5","high":"11.0","low":"10.2","close":"10.8","volume":"2000"},
                        {"datetime":"2026-06-11 00:00:00","open":"10.0","high":"11.0","low":"9.5","close":"10.5","volume":"1000"}
                    ],"status":"ok"}
                    """)));

        var bars = adapter.dailyOhlcHistory("AAPL", 260);

        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isBefore(bars.get(2).date());
        assertThat(bars.get(0).date().toString()).isEqualTo("2026-06-11");
        assertThat(bars.get(2).date().toString()).isEqualTo("2026-06-13");
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(0).high()).isEqualByComparingTo("11.0");
        assertThat(bars.get(0).volume()).isEqualTo(1000L);
        assertThat(bars.get(2).close()).isEqualByComparingTo("11.2");
    }

    @Test void dailyOhlcHistoryThrowsUnavailableOn500() {
        wm.stubFor(get(urlPathEqualTo("/time_series"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter.dailyOhlcHistory("AAPL", 260))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }
}
