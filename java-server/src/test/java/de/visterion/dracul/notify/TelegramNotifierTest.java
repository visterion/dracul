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
    void sendsPlainTextWithoutParseModeForUnderscoreTriggerTypes() {
        // Regression: trigger types contain underscores (PRICE_SPIKE). With
        // parse_mode=Markdown Telegram rejected these with HTTP 400 (unbalanced
        // italic entity). The message must be sent as plain text — no parse_mode.
        wm.stubFor(post(urlPathEqualTo("/bottkn123/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        boolean sent = notifier("tkn123", "99").notifyAlert(
                "NVDA", "PRICE_SPIKE", "CRITICAL", "Sharp move on no news.");

        assertThat(sent).isTrue();
        wm.verify(postRequestedFor(urlPathEqualTo("/bottkn123/sendMessage"))
                .withRequestBody(containing("PRICE_SPIKE"))
                .withRequestBody(notContaining("parse_mode")));
    }

    @Test
    void serverErrorReturnsFalse() {
        wm.stubFor(post(urlPathEqualTo("/bottkn123/sendMessage")).willReturn(aResponse().withStatus(500)));
        assertThat(notifier("tkn123", "99").notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "x")).isFalse();
    }

    @Test
    void notifyDigestPostsWhenConfigured() {
        wm.stubFor(post(urlPathEqualTo("/botTOKEN/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        boolean ok = notifier("TOKEN", "CHAT").notifyDigest("Morgen-Report\nAAA HOLD");

        assertThat(ok).isTrue();
        wm.verify(postRequestedFor(urlPathEqualTo("/botTOKEN/sendMessage"))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("CHAT")))
                .withRequestBody(matchingJsonPath("$.text", containing("Morgen-Report"))));
    }

    @Test
    void notifyDigestNoOpsOnBlankToken() {
        assertThat(notifier("", "CHAT").notifyDigest("x")).isFalse();
    }
}
