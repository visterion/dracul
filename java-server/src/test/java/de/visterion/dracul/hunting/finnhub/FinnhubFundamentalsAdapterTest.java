package de.visterion.dracul.hunting.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FinnhubFundamentalsAdapterTest {

    static WireMockServer wm;
    FinnhubFundamentalsAdapter adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new FinnhubFundamentalsAdapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "test-key");
    }

    @Test
    void parsesMetricObject() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric"))
                .willReturn(okJson("""
                    {"symbol":"ACME","metricType":"all","metric":{
                       "52WeekLow":10.0,"52WeekHigh":40.0,"roaTTM":6.5,
                       "currentRatioQuarterly":1.8,"totalDebt/totalEquityQuarterly":0.4,
                       "grossMarginTTM":35.0,"netProfitMarginTTM":8.0,
                       "revenueGrowthTTMYoy":4.0,"epsGrowthTTMYoy":3.0,
                       "pbAnnual":1.2,"peTTM":11.0,"freeCashFlowPerShareTTM":2.3}}
                    """)));

        BasicFinancials f = adapter.basicFinancials("ACME");

        assertThat(f).isNotNull();
        assertThat(f.week52Low()).isEqualTo(10.0);
        assertThat(f.week52High()).isEqualTo(40.0);
        assertThat(f.roaTtm()).isEqualTo(6.5);
        assertThat(f.currentRatio()).isEqualTo(1.8);
        assertThat(f.debtToEquity()).isEqualTo(0.4);
        assertThat(f.grossMargin()).isEqualTo(35.0);
        assertThat(f.netMargin()).isEqualTo(8.0);
        assertThat(f.revenueGrowthYoy()).isEqualTo(4.0);
        assertThat(f.epsGrowthYoy()).isEqualTo(3.0);
        assertThat(f.priceToBook()).isEqualTo(1.2);
        assertThat(f.peTtm()).isEqualTo(11.0);
        assertThat(f.fcfPerShare()).isEqualTo(2.3);
    }

    @Test
    void absentMetricsBecomeNull() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric"))
                .willReturn(okJson("""
                    {"symbol":"PART","metric":{"52WeekLow":5.0,"roaTTM":1.0}}
                    """)));

        BasicFinancials f = adapter.basicFinancials("PART");

        assertThat(f).isNotNull();
        assertThat(f.week52Low()).isEqualTo(5.0);
        assertThat(f.roaTtm()).isEqualTo(1.0);
        assertThat(f.week52High()).isNull();
        assertThat(f.debtToEquity()).isNull();
        assertThat(f.fcfPerShare()).isNull();
    }

    @Test
    void returnsNullOnServerError() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric"))
                .willReturn(aResponse().withStatus(500)));
        assertThat(adapter.basicFinancials("ACME")).isNull();
    }

    @Test
    void blankKeyShortCircuitsToNull() {
        FinnhubFundamentalsAdapter noKey = new FinnhubFundamentalsAdapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "");
        assertThat(noKey.basicFinancials("ACME")).isNull();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/metric")));
    }
}
