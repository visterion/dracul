package de.visterion.dracul.hunting.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

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
    void parsesFilingsWithAndWithoutTicker() {
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

        List<SpinoffFiling> out = adapter.recentSpinoffs(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).ticker()).isEqualTo("SPN");
        assertThat(out.get(0).companyName()).isEqualTo("ACME SPINCO INC");
        assertThat(out.get(0).filingDate()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(out.get(0).filingUrl()).contains("/Archives/edgar/data/");
        assertThat(out.get(1).ticker()).isEmpty();
        assertThat(out.get(1).companyName()).isEqualTo("NEWCO HOLDINGS");
    }

    @Test
    void returnsEmptyOnServerError() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(aResponse().withStatus(500)));
        assertThat(adapter.recentSpinoffs(
                LocalDate.now().minusDays(60), LocalDate.now())).isEmpty();
    }

    @Test
    void returnsEmptyOnNoHits() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
        assertThat(adapter.recentSpinoffs(
                LocalDate.now().minusDays(60), LocalDate.now())).isEmpty();
    }
}
