package de.visterion.dracul.hunting.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FinnhubEventScreenTest {

    static WireMockServer wm;
    FinnhubEventScreen adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        var news = new FinnhubNewsAdapter(RestClient.builder().baseUrl(wm.baseUrl()).build(), "k");
        adapter = new FinnhubEventScreen(news);
    }

    @Test
    void flagsMergerAndRestatement() {
        wm.stubFor(get(urlPathEqualTo("/company-news")).willReturn(okJson("""
            [{"headline":"Acme to acquire Beta in $2B deal","summary":"","datetime":1900000000},
             {"headline":"Acme announces restatement of Q1 results","summary":"","datetime":1900000001}]
            """)));
        List<String> flags = adapter.confounders("ACME", LocalDate.now().minusDays(5));
        assertThat(flags).contains("m&a", "restatement");
    }

    @Test
    void cleanNewsYieldsNoFlags() {
        wm.stubFor(get(urlPathEqualTo("/company-news")).willReturn(okJson("""
            [{"headline":"Acme opens new store in Texas","summary":"routine update","datetime":1900000000}]
            """)));
        assertThat(adapter.confounders("ACME", LocalDate.now().minusDays(5))).isEmpty();
    }

    @Test
    void distinctFlagsOnly() {
        wm.stubFor(get(urlPathEqualTo("/company-news")).willReturn(okJson("""
            [{"headline":"Acme to acquire Beta","summary":"merger talks","datetime":1900000000},
             {"headline":"Acme acquisition of Gamma confirmed","summary":"","datetime":1900000001}]
            """)));
        assertThat(adapter.confounders("ACME", LocalDate.now().minusDays(5))).containsExactly("m&a");
    }

    @Test
    void emptyNewsYieldsNoFlags() {
        wm.stubFor(get(urlPathEqualTo("/company-news")).willReturn(okJson("[]")));
        assertThat(adapter.confounders("ACME", LocalDate.now().minusDays(5))).isEmpty();
    }
}
