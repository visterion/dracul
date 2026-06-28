package de.visterion.dracul.hunting.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.dracul.strigoi.echo.EarningsRevisions;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FinnhubRevisionsTest {

    static WireMockServer wm;
    FinnhubRevisions adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        var news = new FinnhubNewsAdapter(RestClient.builder().baseUrl(wm.baseUrl()).build(), "k");
        adapter = new FinnhubRevisions(news);
    }

    @Test
    void computesUpwardRevisionProxy() {
        // newest first: net0 = 12+10-1-0 = 21 ; prev = 8+9-2-1 = 14 ; proxy = +7 -> up
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation")).willReturn(okJson("""
            [{"period":"2026-06-01","strongBuy":12,"buy":10,"hold":3,"sell":1,"strongSell":0},
             {"period":"2026-05-01","strongBuy":8,"buy":9,"hold":4,"sell":2,"strongSell":1}]
            """)));
        EarningsRevisions r = adapter.revisions("ACME");
        assertThat(r.available()).isTrue();
        assertThat(r.netProxy()).isEqualTo(7);
        assertThat(r.direction()).isEqualTo("up");
    }

    @Test
    void downwardRevisionIsDown() {
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation")).willReturn(okJson("""
            [{"period":"2026-06-01","strongBuy":1,"buy":1,"hold":3,"sell":5,"strongSell":3},
             {"period":"2026-05-01","strongBuy":8,"buy":9,"hold":4,"sell":2,"strongSell":1}]
            """)));
        EarningsRevisions r = adapter.revisions("ACME");
        assertThat(r.netProxy()).isLessThan(0);
        assertThat(r.direction()).isEqualTo("down");
    }

    @Test
    void singlePeriodIsFlatZero() {
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation")).willReturn(okJson("""
            [{"period":"2026-06-01","strongBuy":5,"buy":5,"hold":1,"sell":1,"strongSell":0}]
            """)));
        EarningsRevisions r = adapter.revisions("ACME");
        assertThat(r.available()).isTrue();
        assertThat(r.netProxy()).isEqualTo(0);
        assertThat(r.direction()).isEqualTo("flat");
    }

    @Test
    void emptyTrendIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation")).willReturn(okJson("[]")));
        assertThat(adapter.revisions("ACME").available()).isFalse();
    }
}
