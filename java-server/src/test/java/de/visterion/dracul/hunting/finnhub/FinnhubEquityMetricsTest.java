package de.visterion.dracul.hunting.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FinnhubEquityMetricsTest {

    static WireMockServer wm;
    FinnhubEquityMetrics adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new FinnhubEquityMetrics(RestClient.builder().baseUrl(wm.baseUrl()).build(), "test-key");
    }

    @Test
    void parsesMetricAndSector() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric")).willReturn(okJson("""
            {"symbol":"AAPL","metric":{"beta":1.25,"marketCapitalization":3000000.0,
               "52WeekLow":150.0,"52WeekHigh":260.0}}
            """)));
        wm.stubFor(get(urlPathEqualTo("/stock/profile2")).willReturn(okJson("""
            {"ticker":"AAPL","finnhubIndustry":"Technology"}
            """)));

        EquityMetrics m = adapter.metrics("AAPL");

        assertThat(m.available()).isTrue();
        assertThat(m.beta()).isEqualTo(1.25);
        assertThat(m.marketCap()).isEqualTo(3_000_000.0);
        assertThat(m.week52Low()).isEqualTo(150.0);
        assertThat(m.week52High()).isEqualTo(260.0);
        assertThat(m.sector()).isEqualTo("Technology");
    }

    @Test
    void metricOkButProfileFailsStillAvailableWithNullSector() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric")).willReturn(okJson("""
            {"symbol":"X","metric":{"beta":0.9}}
            """)));
        wm.stubFor(get(urlPathEqualTo("/stock/profile2")).willReturn(aResponse().withStatus(500)));

        EquityMetrics m = adapter.metrics("X");

        assertThat(m.available()).isTrue();
        assertThat(m.beta()).isEqualTo(0.9);
        assertThat(m.marketCap()).isNull();
        assertThat(m.sector()).isNull();
    }

    @Test
    void serverErrorOnMetricBecomesUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric")).willReturn(aResponse().withStatus(500)));
        assertThat(adapter.metrics("X").available()).isFalse();
    }

    @Test
    void blankKeyShortCircuitsToUnavailable() {
        var noKey = new FinnhubEquityMetrics(RestClient.builder().baseUrl(wm.baseUrl()).build(), "");
        assertThat(noKey.metrics("X").available()).isFalse();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/metric")));
    }

    @Test
    void connectionFaultOnMetricBecomesUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(adapter.metrics("X").available()).isFalse();
    }

    @Test
    void profileWithoutIndustryFieldYieldsNullSector() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric")).willReturn(okJson("""
            {"symbol":"X","metric":{"beta":1.0}}
            """)));
        wm.stubFor(get(urlPathEqualTo("/stock/profile2")).willReturn(okJson("{}")));

        EquityMetrics m = adapter.metrics("X");

        assertThat(m.available()).isTrue();
        assertThat(m.beta()).isEqualTo(1.0);
        assertThat(m.sector()).isNull();
    }
}
