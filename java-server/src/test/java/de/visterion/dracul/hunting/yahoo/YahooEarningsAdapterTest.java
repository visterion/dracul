package de.visterion.dracul.hunting.yahoo;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

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
    void parsesPositiveSurpriseRow() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("""
                    {"rows":[
                        {"ticker":"aapl","companyshortname":"Apple Inc.",
                         "startdatetime":"2026-05-20T20:00:00.000Z",
                         "epsestimate":1.50,"epsactual":1.65,"epssurprisepct":10.0}
                    ]}
                    """)));

        List<EarningsEvent> events = adapter.recentEarnings(
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));

        assertThat(events).hasSize(1);
        var e = events.get(0);
        assertThat(e.symbol()).isEqualTo("AAPL");
        assertThat(e.companyName()).isEqualTo("Apple Inc.");
        assertThat(e.reportDate()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(e.epsActual()).isEqualByComparingTo("1.65");
        assertThat(e.epsEstimate()).isEqualByComparingTo("1.50");
        assertThat(e.surprisePercent()).isEqualByComparingTo("10.0");
    }

    @Test
    void returnsEmptyListOnServerError() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(adapter.recentEarnings(
                LocalDate.now().minusDays(1), LocalDate.now())).isEmpty();
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

        assertThat(adapter.recentEarnings(
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21))).isEmpty();
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

        var events = adapter.recentEarnings(
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).epsActual()).isNull();
        assertThat(events.get(0).surprisePercent()).isNull();
    }
}
