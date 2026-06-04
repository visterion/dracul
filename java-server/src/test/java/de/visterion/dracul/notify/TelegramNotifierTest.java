package de.visterion.dracul.notify;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class TelegramNotifierTest {

    static WireMockServer wm;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private TelegramNotifier notifier(String token, String chatId) {
        // Force HTTP/1.1 so WireMock (HTTP/1.1 only) does not get HTTP/2 upgrade attempts.
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        return new TelegramNotifier(
                RestClient.builder().baseUrl(wm.baseUrl()).requestFactory(factory).build(),
                token, chatId);
    }

    @Test
    void sendsMessageAndReturnsTrue() {
        wm.stubFor(post(urlPathEqualTo("/bottkn123/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        boolean sent = notifier("tkn123", "99").notifyAlert(
                "AAPL", "PRICE_SPIKE", "CRITICAL", "Sharp move, no news.");

        assertThat(sent).isTrue();
        wm.verify(postRequestedFor(urlPathEqualTo("/bottkn123/sendMessage"))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("99")))
                .withRequestBody(matchingJsonPath("$.text")));
    }

    @Test
    void blankTokenReturnsFalseWithoutHttp() {
        assertThat(notifier("", "99").notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "x")).isFalse();
        assertThat(notifier("tkn123", "").notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "x")).isFalse();
    }

    @Test
    void serverErrorReturnsFalse() {
        wm.stubFor(post(urlPathEqualTo("/bottkn123/sendMessage")).willReturn(aResponse().withStatus(500)));
        assertThat(notifier("tkn123", "99").notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "x")).isFalse();
    }
}
