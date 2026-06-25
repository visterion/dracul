package de.visterion.dracul.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

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

    private void stubChart() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{"regularMarketPrice":1.0,"longName":"Apple Inc."},
                        "indicators":{"quote":[{"close":[1.0]}]}
                    }],"error":null}}
                    """)));
    }

    @Test
    void defaultYahooUserAgentIsNotTheBlockedLinuxVariant() {
        assertThat(MarketDataConfig.DEFAULT_YAHOO_USER_AGENT)
                .as("Yahoo 429s the Linux-Chrome UA; default must be a non-blocked browser UA")
                .doesNotContain("X11; Linux")
                .doesNotContain("Linux x86_64")
                .contains("Mozilla/5.0")
                .contains("Windows");
    }

    @Test
    void yahooClientSendsConfiguredUserAgent() {
        stubChart();

        String ua = "Mozilla/5.0 (Test) Chrome/124.0.0.0";
        RestClient client = new MarketDataConfig().yahooRestClient(wm.baseUrl(), ua, 5000L);
        new YahooMarketDataAdapter(client).resolve("AAPL");

        wm.verify(getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withHeader("User-Agent", equalTo(ua)));
    }

    @Test
    void blankUserAgentFallsBackToWindowsDefaultOverTheWire() {
        stubChart();

        RestClient client = new MarketDataConfig().yahooRestClient(wm.baseUrl(), "", 5000L);
        new YahooMarketDataAdapter(client).resolve("AAPL");

        wm.verify(getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withHeader("User-Agent", equalTo(MarketDataConfig.DEFAULT_YAHOO_USER_AGENT)));
    }
}
