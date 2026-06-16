package de.visterion.dracul.hunting.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarMergerAdapterTest {

    static WireMockServer wm;
    EdgarMergerAdapter adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new EdgarMergerAdapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "https://sec.example");
    }

    @Test
    void returnsHealthyWithDeals() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0001234567-26-000123:proxy.htm","_source":{
                         "display_names":["TARGET CORP  (CIK 0001234567)"],
                         "tickers":["TGT"],"file_date":"2026-05-20","file_type":"DEFM14A"}},
                      {"_id":"0007654321-26-000045:to.htm","_source":{
                         "display_names":["ACQUIRED INC  (CIK 0007654321)"],
                         "tickers":["AQD"],"file_date":"2026-05-22","file_type":"SC TO-T"}}
                    ]}}
                    """)));

        DataSourceResult<MergerFiling> r = adapter.recentDeals(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.health().source()).isEqualTo("edgar");
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).ticker()).isEqualTo("TGT");
        assertThat(r.items().get(0).companyName()).isEqualTo("TARGET CORP");
        assertThat(r.items().get(0).formType()).isEqualTo("DEFM14A");
        assertThat(r.items().get(0).filingDate()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(r.items().get(0).filingUrl()).contains("/Archives/edgar/data/");
        assertThat(r.items().get(1).ticker()).isEqualTo("AQD");
        assertThat(r.items().get(1).formType()).isEqualTo("SC TO-T");
    }

    @Test
    void serverErrorIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(aResponse().withStatus(500)));
        DataSourceResult<MergerFiling> r = adapter.recentDeals(
                LocalDate.now().minusDays(45), LocalDate.now());
        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().detail()).contains("edgar");
        assertThat(r.items()).isEmpty();
    }

    @Test
    void returnsHealthyEmptyOnNoHits() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
        DataSourceResult<MergerFiling> r = adapter.recentDeals(
                LocalDate.now().minusDays(45), LocalDate.now());
        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.items()).isEmpty();
    }
}
