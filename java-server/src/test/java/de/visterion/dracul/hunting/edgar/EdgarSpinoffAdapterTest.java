package de.visterion.dracul.hunting.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarSpinoffAdapterTest {

    static WireMockServer wm;
    EdgarSpinoffAdapter adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new EdgarSpinoffAdapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "https://sec.example");
    }

    @Test
    void returnsHealthyWithFilings() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0001234567-26-000123:spinco.htm","_source":{
                         "display_names":["ACME SPINCO INC  (CIK 0001234567)"],
                         "tickers":["SPN"],"file_date":"2026-05-20","file_type":"10-12B"}},
                      {"_id":"0007654321-26-000045:newco.htm","_source":{
                         "display_names":["NEWCO HOLDINGS  (CIK 0007654321)"],
                         "file_date":"2026-05-22","file_type":"10-12B"}}
                    ]}}
                    """)));

        DataSourceResult<SpinoffFiling> r = adapter.recentSpinoffs(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.health().source()).isEqualTo("edgar");
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).ticker()).isEqualTo("SPN");
        assertThat(r.items().get(0).companyName()).isEqualTo("ACME SPINCO INC");
        assertThat(r.items().get(0).filingDate()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(r.items().get(0).filingUrl()).contains("/Archives/edgar/data/");
        assertThat(r.items().get(1).ticker()).isEmpty();
        assertThat(r.items().get(1).companyName()).isEqualTo("NEWCO HOLDINGS");
    }

    @Test
    void serverErrorIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(aResponse().withStatus(500)));
        DataSourceResult<SpinoffFiling> r = adapter.recentSpinoffs(
                LocalDate.now().minusDays(30), LocalDate.now());
        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().detail()).contains("edgar");
        assertThat(r.items()).isEmpty();
    }

    @Test
    void returnsHealthyEmptyOnNoHits() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
        DataSourceResult<SpinoffFiling> r = adapter.recentSpinoffs(
                LocalDate.now().minusDays(60), LocalDate.now());
        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.items()).isEmpty();
    }
}
