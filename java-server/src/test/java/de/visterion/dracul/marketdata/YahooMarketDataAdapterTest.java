package de.visterion.dracul.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class YahooMarketDataAdapterTest {

    static WireMockServer wm;
    YahooMarketDataAdapter adapter;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new YahooMarketDataAdapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build());
    }

    @Test
    void resolvesSymbolWithPriceAndHistory() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{"regularMarketPrice":190.5,"longName":"Apple Inc."},
                        "indicators":{"quote":[{"close":[180.0,182.0,190.5]}]}
                    }],"error":null}}
                    """)));

        MarketData md = adapter.resolve("AAPL");

        assertThat(md.companyName()).isEqualTo("Apple Inc.");
        assertThat(md.currentPrice()).isEqualByComparingTo(new BigDecimal("190.5"));
        assertThat(md.priceHistory30d()).hasSize(3);
    }

    @Test
    void throwsNotFoundWhenResultArrayEmpty() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/ZZZZ"))
                .willReturn(okJson("""
                    {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}
                    """)));

        assertThatThrownBy(() -> adapter.resolve("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                    assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test
    void throwsUnavailableOn500() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter.resolve("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                    assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test
    void fallsBackToShortNameWhenLongNameMissing() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/BRK-B"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{"regularMarketPrice":405.0,"shortName":"Berkshire Hathaway B"},
                        "indicators":{"quote":[{"close":[400.0,405.0]}]}
                    }],"error":null}}
                    """)));

        MarketData md = adapter.resolve("BRK-B");
        assertThat(md.companyName()).isEqualTo("Berkshire Hathaway B");
    }

    @Test
    void dailyOhlcHistoryReturnsBarsOldestFirst() {
        // Yahoo returns oldest-first; adapter must NOT reverse
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("1y"))
                .withQueryParam("interval", equalTo("1d"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "timestamp":[1749600000,1749686400,1749772800],
                        "indicators":{"quote":[{
                            "open":[10.0,10.5,11.0],
                            "high":[11.0,11.0,11.5],
                            "low":[9.5,10.2,10.8],
                            "close":[10.5,10.8,11.2],
                            "volume":[1000,2000,3000]
                        }]}
                    }],"error":null}}
                    """)));

        var bars = adapter.dailyOhlcHistory("AAPL", 260);

        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isBefore(bars.get(2).date());
        assertThat(bars.get(2).close()).isEqualByComparingTo("11.2");
        assertThat(bars.get(2).high()).isEqualByComparingTo("11.5");
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(0).volume()).isEqualTo(1000L);
    }

    @Test
    void dailyOhlcHistorySkipsNullCloseEntries() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("1y"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "timestamp":[1749600000,1749686400,1749772800],
                        "indicators":{"quote":[{
                            "open":[10.0,null,11.0],
                            "high":[11.0,null,11.5],
                            "low":[9.5,null,10.8],
                            "close":[10.5,null,11.2],
                            "volume":[1000,null,3000]
                        }]}
                    }],"error":null}}
                    """)));

        var bars = adapter.dailyOhlcHistory("AAPL", 260);

        assertThat(bars).hasSize(2);
        // Verify that the correct bars survived (index 0 and 2 — the middle null bar must be gone)
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(1).close()).isEqualByComparingTo("11.2");
        // Dates must be the first and third timestamps (1749600000 = 2025-06-11, 1749772800 = 2025-06-13)
        assertThat(bars.get(0).date()).isBefore(bars.get(1).date());
    }

    @Test
    void dailyOhlcHistoryThrowsUnavailableOn500() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("1y"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter.dailyOhlcHistory("AAPL", 260))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }
}
