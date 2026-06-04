package de.visterion.dracul.hunting.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FinnhubNewsAdapterTest {

    static WireMockServer wm;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private FinnhubNewsAdapter withKey(String key) {
        return new FinnhubNewsAdapter(RestClient.builder().baseUrl(wm.baseUrl()).build(), key);
    }

    @Test
    void parsesCompanyNews() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .willReturn(okJson("""
                    [{"headline":"Acme cuts guidance","summary":"Q2 miss",
                      "source":"Reuters","datetime":1716200000,"url":"http://n/1"}]
                    """)));

        List<NewsHeadline> news = withKey("k")
                .companyNews("ACME", LocalDate.of(2026, 5, 19), LocalDate.of(2026, 5, 20));

        assertThat(news).hasSize(1);
        assertThat(news.get(0).headline()).isEqualTo("Acme cuts guidance");
        assertThat(news.get(0).source()).isEqualTo("Reuters");
    }

    @Test
    void parsesRecommendationTrend() {
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation"))
                .willReturn(okJson("""
                    [{"period":"2026-05-01","strongBuy":1,"buy":2,"hold":3,"sell":4,"strongSell":1},
                     {"period":"2026-04-01","strongBuy":3,"buy":4,"hold":2,"sell":1,"strongSell":0}]
                    """)));

        List<RecommendationTrend> trends = withKey("k").recommendationTrend("ACME");

        assertThat(trends).hasSize(2);
        assertThat(trends.get(0).period()).isEqualTo("2026-05-01");
        assertThat(trends.get(0).sell()).isEqualTo(4);
    }

    @Test
    void missingApiKeyReturnsEmptyWithoutHttp() {
        // No stub registered; a blank key must short-circuit before any HTTP call.
        assertThat(withKey("").companyNews("ACME", LocalDate.now().minusDays(1), LocalDate.now())).isEmpty();
        assertThat(withKey("").recommendationTrend("ACME")).isEmpty();
    }

    @Test
    void serverErrorReturnsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/company-news")).willReturn(aResponse().withStatus(500)));
        assertThat(withKey("k").companyNews("ACME", LocalDate.now().minusDays(1), LocalDate.now())).isEmpty();
    }
}
