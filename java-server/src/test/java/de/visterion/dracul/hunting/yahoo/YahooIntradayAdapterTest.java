package de.visterion.dracul.hunting.yahoo;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class YahooIntradayAdapterTest {

    static WireMockServer wm;
    YahooIntradayAdapter adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new YahooIntradayAdapter(RestClient.builder().baseUrl(wm.baseUrl()).build());
    }

    @Test
    void parsesClosesAndVolumes() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{"indicators":{"quote":[
                        {"close":[100.0,101.5,null,104.0],"volume":[1000,1200,null,5000]}
                    ]}}]}}
                    """)));

        IntradayCandles c = adapter.intradayCandles("AAPL");

        assertThat(c.closes()).hasSize(3);
        assertThat(c.closes().get(2)).isEqualByComparingTo("104.0");
        assertThat(c.volumes()).containsExactly(1000L, 1200L, 5000L);
    }

    @Test
    void returnsEmptyOnServerError() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(aResponse().withStatus(500)));
        assertThat(adapter.intradayCandles("AAPL").isEmpty()).isTrue();
    }

    @Test
    void returnsEmptyOnMissingResult() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("{\"chart\":{\"result\":[]}}")));
        assertThat(adapter.intradayCandles("AAPL").isEmpty()).isTrue();
    }
}
