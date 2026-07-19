package de.visterion.dracul.hivemem;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the configured {@code timeoutMs} actually bounds the wall-clock against a real
 * transport (a hung socket), rather than only being asserted-to-throw-eventually. WireMock
 * accepts the TCP connection immediately but delays every response body well beyond the
 * client's configured timeout, exercising the real {@code HttpClientStreamableHttpTransport} +
 * {@code McpSyncClient} stack end to end.
 */
class HiveMemClientTimeoutTest {

    private static final long TIMEOUT_MS = 500;
    private static final long SLACK_MS = 3000;
    private static final long WIREMOCK_DELAY_MS = 10_000;

    private static WireMockServer wm;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/mcp"))
                .willReturn(aResponse().withFixedDelay((int) WIREMOCK_DELAY_MS).withStatus(200)));
    }

    @Test void callToolWriteBoundedByTimeoutOnHungSocket() {
        HiveMemClient client = new HiveMemClient(wm.baseUrl(), "", TIMEOUT_MS);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.callToolWrite("add_cell", null))
                .isInstanceOf(HiveMemUnavailableException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("single-attempt write posture should not wait past timeoutMs + slack")
                .isLessThan(TIMEOUT_MS + SLACK_MS);
    }

    @Test void callToolReadBoundedByTwiceTimeoutOnHungSocket() {
        HiveMemClient client = new HiveMemClient(wm.baseUrl(), "", TIMEOUT_MS);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.callToolRead("search", null))
                .isInstanceOf(HiveMemUnavailableException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("reconnect-once read posture retries once, so bound is ~2x timeoutMs + slack")
                .isLessThan(2 * TIMEOUT_MS + SLACK_MS);
    }
}
