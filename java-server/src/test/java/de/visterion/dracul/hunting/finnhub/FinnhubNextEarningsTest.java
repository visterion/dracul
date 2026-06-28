package de.visterion.dracul.hunting.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FinnhubNextEarningsTest {

    static WireMockServer wm;
    FinnhubNextEarnings adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new FinnhubNextEarnings(RestClient.builder().baseUrl(wm.baseUrl()).build(), "k");
    }

    @Test
    void picksEarliestFutureDate() {
        LocalDate d1 = LocalDate.now().plusDays(40);
        LocalDate d2 = LocalDate.now().plusDays(12);
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings")).willReturn(okJson(
            "{\"earningsCalendar\":[{\"symbol\":\"ACME\",\"date\":\"" + d1 + "\"}," +
            "{\"symbol\":\"ACME\",\"date\":\"" + d2 + "\"}]}")));
        Optional<LocalDate> next = adapter.nextEarningsDate("ACME");
        assertThat(next).contains(d2);
    }

    @Test
    void ignoresPastDates() {
        LocalDate past = LocalDate.now().minusDays(5);
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings")).willReturn(okJson(
            "{\"earningsCalendar\":[{\"symbol\":\"ACME\",\"date\":\"" + past + "\"}]}")));
        assertThat(adapter.nextEarningsDate("ACME")).isEmpty();
    }

    @Test
    void blankKeyIsEmpty() {
        var noKey = new FinnhubNextEarnings(RestClient.builder().baseUrl(wm.baseUrl()).build(), "");
        assertThat(noKey.nextEarningsDate("ACME")).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/calendar/earnings")));
    }

    @Test
    void serverErrorIsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings")).willReturn(aResponse().withStatus(500)));
        assertThat(adapter.nextEarningsDate("ACME")).isEmpty();
    }
}
