package de.visterion.dracul.hunting.yahoo;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class YahooEarningsAdapterTest {

    static WireMockServer wm;
    YahooEarningsAdapter adapter;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new YahooEarningsAdapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build());
    }

    @Test
    void returnsHealthyWithEvents() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("""
                    {"rows":[
                        {"ticker":"aapl","companyshortname":"Apple Inc.",
                         "startdatetime":"2026-05-20T20:00:00.000Z",
                         "epsestimate":1.50,"epsactual":1.65,"epssurprisepct":10.0}
                    ]}
                    """)));

        DataSourceResult<EarningsEvent> r = adapter.recentEarnings(
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));

        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.health().source()).isEqualTo("yahoo");
        assertThat(r.items()).hasSize(1);
        var e = r.items().get(0);
        assertThat(e.symbol()).isEqualTo("AAPL");
        assertThat(e.companyName()).isEqualTo("Apple Inc.");
        assertThat(e.reportDate()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(e.epsActual()).isEqualByComparingTo("1.65");
        assertThat(e.epsEstimate()).isEqualByComparingTo("1.50");
        assertThat(e.surprisePercent()).isEqualByComparingTo("10.0");
    }

    @Test
    void unavailableWhenRetriesExhausted() {
        // Stub both attempts (fetchWithRetry tries up to 2 times)
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(aResponse().withStatus(500)));

        DataSourceResult<EarningsEvent> r = adapter.recentEarnings(
                LocalDate.now().minusDays(1), LocalDate.now());

        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().detail()).contains("yahoo");
        assertThat(r.items()).isEmpty();
    }

    @Test
    void returnsHealthyEmptyOnNoRows() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("{\"rows\":[]}")));

        DataSourceResult<EarningsEvent> r = adapter.recentEarnings(
                LocalDate.now().minusDays(1), LocalDate.now());

        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.items()).isEmpty();
    }

    @Test
    void skipsRowWithoutTicker() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("""
                    {"rows":[
                        {"companyshortname":"No Ticker Co","startdatetime":"2026-05-20T20:00:00.000Z",
                         "epsestimate":1.0,"epsactual":1.1,"epssurprisepct":10.0}
                    ]}
                    """)));

        DataSourceResult<EarningsEvent> r = adapter.recentEarnings(
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));

        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.items()).isEmpty();
    }

    @Test
    void parsesMissingEpsAsNull() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("""
                    {"rows":[
                        {"ticker":"NEW","companyshortname":"New Issue",
                         "startdatetime":"2026-05-20T20:00:00.000Z"}
                    ]}
                    """)));

        DataSourceResult<EarningsEvent> r = adapter.recentEarnings(
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));

        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).epsActual()).isNull();
        assertThat(r.items().get(0).surprisePercent()).isNull();
    }
}
