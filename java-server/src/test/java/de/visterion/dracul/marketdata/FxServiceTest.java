package de.visterion.dracul.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FxServiceTest {
    static WireMockServer wm;
    FxService fx;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll  static void stop()  { wm.stop(); }
    @BeforeEach void setUp() {
        wm.resetAll();
        fx = new FxService(RestClient.builder().baseUrl(wm.baseUrl()).build());
    }

    @Test void identityWhenSameCurrency() {
        assertThat(fx.convert(new BigDecimal("100"), "EUR", "EUR")).isEqualByComparingTo("100");
    }

    @Test void convertWithoutWarmIsIdentityAndDoesNoHttp() {
        assertThat(fx.convert(new BigDecimal("100"), "USD", "EUR")).isEqualByComparingTo("100");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/v8/finance/chart/USDEUR=X")));
    }

    @Test void warmThenConvertUsesWarmedRate() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/USDEUR=X")).willReturn(okJson("""
            {"chart":{"result":[{"meta":{"regularMarketPrice":0.90}}],"error":null}}""")));
        fx.warm("USD", "EUR");
        assertThat(fx.convert(new BigDecimal("100"), "USD", "EUR")).isEqualByComparingTo("90.00");
    }

    @Test void warmFetchesOnce_convertDoesNotRefetch() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/USDEUR=X")).willReturn(okJson("""
            {"chart":{"result":[{"meta":{"regularMarketPrice":0.90}}],"error":null}}""")));
        fx.warm("USD", "EUR");
        fx.convert(new BigDecimal("1"), "USD", "EUR");
        fx.convert(new BigDecimal("1"), "USD", "EUR");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/v8/finance/chart/USDEUR=X")));
    }

    @Test void warmFailureLeavesIdentity() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/USDEUR=X")).willReturn(aResponse().withStatus(500)));
        fx.warm("USD", "EUR");
        assertThat(fx.convert(new BigDecimal("100"), "USD", "EUR")).isEqualByComparingTo("100");
    }
}
