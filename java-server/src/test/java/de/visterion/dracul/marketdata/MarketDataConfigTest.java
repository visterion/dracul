package de.visterion.dracul.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

class MarketDataConfigTest {

    static WireMockServer wm;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { wm.stop(); }

    @BeforeEach
    void reset() { wm.resetAll(); }

    @Test
    void yahooClientSendsConfiguredUserAgent() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{"regularMarketPrice":1.0,"longName":"Apple Inc."},
                        "indicators":{"quote":[{"close":[1.0]}]}
                    }],"error":null}}
                    """)));

        String ua = "Mozilla/5.0 (Test) Chrome/124.0.0.0";
        RestClient client = new MarketDataConfig().yahooRestClient(wm.baseUrl(), ua, 5000L);
        new YahooMarketDataAdapter(client).resolve("AAPL");

        wm.verify(getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withHeader("User-Agent", equalTo(ua)));
    }
}
