package de.visterion.dracul.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FinnhubMarketDataAdapterTest {

    WireMockServer wm;
    FinnhubMarketDataAdapter adapter;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        RestClient client = RestClient.builder().baseUrl(wm.baseUrl()).build();
        adapter = new FinnhubMarketDataAdapter(client, "test-key");
    }

    @AfterEach
    void tearDown() { wm.stop(); }

    @Test
    void quotesParsesPriceAndDayChange() {
        wm.stubFor(get(urlPathEqualTo("/quote")).withQueryParam("symbol", equalTo("AVGO"))
                .willReturn(okJson("{\"c\":382.07,\"d\":-3.5,\"dp\":-0.9077,\"pc\":385.57}")));
        Map<String, Quote> out = adapter.quotes(List.of("AVGO"));
        assertThat(out).containsKey("AVGO");
        assertThat(out.get("AVGO").price()).isEqualByComparingTo("382.07");
        assertThat(out.get("AVGO").dayChangePercent()).isEqualByComparingTo("-0.9077");
    }

    @Test
    void unknownSymbolZeroPriceOmitted() {
        wm.stubFor(get(urlPathEqualTo("/quote")).withQueryParam("symbol", equalTo("ZZZZ"))
                .willReturn(okJson("{\"c\":0,\"d\":null,\"dp\":null,\"pc\":0}")));
        assertThat(adapter.quotes(List.of("ZZZZ"))).doesNotContainKey("ZZZZ");
    }

    @Test
    void httpErrorOmitsSymbol() {
        wm.stubFor(get(urlPathEqualTo("/quote")).withQueryParam("symbol", equalTo("AVGO"))
                .willReturn(aResponse().withStatus(500)));
        assertThat(adapter.quotes(List.of("AVGO"))).isEmpty();
    }

    @Test
    void blankKeyShortCircuitsToEmpty() {
        RestClient client = RestClient.builder().baseUrl(wm.baseUrl()).build();
        FinnhubMarketDataAdapter blank = new FinnhubMarketDataAdapter(client, "");
        assertThat(blank.quotes(List.of("AVGO"))).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/quote")));
    }

    @Test
    void resolveThrowsBecauseQuoteOnly() {
        assertThatThrownBy(() -> adapter.resolve("AVGO"))
                .isInstanceOf(MarketDataException.class);
    }
}
