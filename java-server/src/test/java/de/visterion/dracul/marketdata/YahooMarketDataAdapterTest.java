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
}
