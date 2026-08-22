package de.visterion.dracul.notify;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.stream.Collectors;

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

    /** Bodies of every sendMessage POST, in the order WireMock received them. */
    private List<String> sentTexts(String token) {
        var mapper = new tools.jackson.databind.ObjectMapper();
        return wm.findAll(postRequestedFor(urlPathEqualTo("/bot" + token + "/sendMessage")))
                .stream()
                .map(r -> mapper.readTree(r.getBodyAsString()).path("text").asText())
                .toList();
    }

    /** Strips the "[i/n]\n" marker line from a part body. */
    private static String withoutMarker(String body) {
        return body.substring(body.indexOf('\n') + 1);
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
            attachAppender() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(TelegramNotifier.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** Each test attaches its own appender; without this they pile up on the logger. */
    @AfterEach
    void detachAppenders() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TelegramNotifier.class))
                .detachAndStopAllAppenders();
    }

    private static List<String> lines(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> a) {
        return a.list.stream().map(e -> e.getFormattedMessage()).toList();
    }

    @Test
    void messageUnderTheBudgetIsSentUnchangedAsOnePart() {
        wm.stubFor(post(urlPathEqualTo("/botTOKEN/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        boolean ok = notifier("TOKEN", "CHAT").notifyDigest("Morgen-Report\nAAA HOLD\n");

        assertThat(ok).isTrue();
        assertThat(sentTexts("TOKEN")).containsExactly("Morgen-Report\nAAA HOLD\n");
    }

    @Test
    void longMessageIsSplitIntoOrderedMarkedPartsLosingNothing() {
        wm.stubFor(post(urlPathEqualTo("/botTOKEN/sendMessage")).willReturn(okJson("{\"ok\":true}")));
        // 60 lines x 100 chars = 6000 chars, over the 3988 budget, under twice it.
        String text = ("x".repeat(99) + "\n").repeat(60);

        boolean ok = notifier("TOKEN", "CHAT").notifyDigest(text);

        assertThat(ok).isTrue();
        List<String> texts = sentTexts("TOKEN");
        assertThat(texts).hasSize(2);
        assertThat(texts.get(0)).startsWith("[1/2]\n");
        assertThat(texts.get(1)).startsWith("[2/2]\n");
        assertThat(texts).allSatisfy(t -> assertThat(t.length()).isLessThanOrEqualTo(4096));
        assertThat(texts.stream().map(TelegramNotifierTest::withoutMarker)
                .collect(Collectors.joining())).isEqualTo(text);
    }

    @Test
    void splitHappensAtLineBoundariesNotMidLine() {
        // Every line is 100 chars incl. its newline, so an intact part is a multiple of 100.
        String text = ("x".repeat(99) + "\n").repeat(60);

        List<String> parts = TelegramNotifier.split(text, TelegramNotifier.CHUNK_BUDGET);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).endsWith("\n");
        assertThat(parts.get(0).length() % 100).isZero();
        assertThat(String.join("", parts)).isEqualTo(text);
    }

    @Test
    void singleLineLongerThanTheBudgetIsHardSplitAndNothingIsLost() {
        String text = "y".repeat(9000);   // one line, no newline at all

        List<String> parts = TelegramNotifier.split(text, TelegramNotifier.CHUNK_BUDGET);

        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).hasSize(TelegramNotifier.CHUNK_BUDGET);
        assertThat(parts.get(1)).hasSize(TelegramNotifier.CHUNK_BUDGET);
        assertThat(String.join("", parts)).isEqualTo(text);
    }

    @Test
    void splitIsAnnouncedOnceAtInfo() {
        wm.stubFor(post(urlPathEqualTo("/botTOKEN/sendMessage")).willReturn(okJson("{\"ok\":true}")));
        String text = ("x".repeat(99) + "\n").repeat(60);
        var appender = attachAppender();

        notifier("TOKEN", "CHAT").notifyDigest(text);

        assertThat(lines(appender)).contains("telegram message split: parts=2 chars=6000");
    }

    @Test
    void failedSecondPartStopsTheSendAndSaysHowFarItGot() {
        String url = "/botTOKEN/sendMessage";
        wm.stubFor(post(urlPathEqualTo(url)).inScenario("parts")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson("{\"ok\":true}")).willSetStateTo("second"));
        wm.stubFor(post(urlPathEqualTo(url)).inScenario("parts")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(400).withBody("{\"ok\":false}")));
        String text = ("x".repeat(99) + "\n").repeat(60);
        var appender = attachAppender();

        boolean ok = notifier("TOKEN", "CHAT").notifyDigest(text);

        assertThat(ok).isFalse();
        assertThat(sentTexts("TOKEN")).hasSize(2);          // part 1 really went out
        // The exception text is the RestClient's, not ours — assert the stable prefix only.
        assertThat(lines(appender))
                .anySatisfy(l -> assertThat(l)
                        .startsWith("telegram digest incomplete: sent 1 of 2 parts — "));
        assertThat(lines(appender)).noneSatisfy(
                l -> assertThat(l).startsWith("Telegram push failed:"));
    }

    @Test
    void singlePartFailureKeepsTheOriginalWarnLine() {
        wm.stubFor(post(urlPathEqualTo("/botTOKEN/sendMessage"))
                .willReturn(aResponse().withStatus(400).withBody("{\"ok\":false}")));
        var appender = attachAppender();

        assertThat(notifier("TOKEN", "CHAT").notifyDigest("kurz")).isFalse();

        assertThat(lines(appender))
                .anySatisfy(l -> assertThat(l).startsWith("Telegram push failed: "));
    }
}
