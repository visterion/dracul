package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the reconnect-once retry structure in {@link AgoraClient#callTool} by overriding the
 * package-private {@code attempt(...)} seam. No real transport is touched: {@code closeQuietly()}
 * with a null client is a harmless no-op.
 */
class AgoraClientReconnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Subclass whose {@code attempt} is driven by a per-call function; counts invocations. */
    private static final class StubClient extends AgoraClient {
        final AtomicInteger calls = new AtomicInteger();
        private final IntFunction<JsonNode> behaviour;

        StubClient(IntFunction<JsonNode> behaviour) {
            super("http://unused", "", 8000, 5000);
            this.behaviour = behaviour;
        }

        @Override
        JsonNode attempt(String name, Map<String, Object> argsMap) {
            int n = calls.incrementAndGet();
            return behaviour.apply(n);
        }
    }

    @Test void reconnectsAndRetriesOnTransientFailure() {
        JsonNode ok = MAPPER.readTree("{\"ok\":true}");
        StubClient client = new StubClient(n -> {
            if (n == 1) throw new RuntimeException("stale");
            return ok;
        });
        JsonNode result = client.callTool("get_quote", null);
        assertThat(result).isSameAs(ok);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test void doesNotReconnectOnUnavailable() {
        StubClient client = new StubClient(n -> { throw new AgoraUnavailableException("down"); });
        assertThatThrownBy(() -> client.callTool("get_quote", null))
                .isInstanceOf(AgoraUnavailableException.class);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test void wrapsAsUnavailableAfterRetryAlsoFails() {
        StubClient client = new StubClient(n -> { throw new RuntimeException("boom"); });
        assertThatThrownBy(() -> client.callTool("get_quote", null))
                .isInstanceOf(AgoraUnavailableException.class);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    /** Regression guard: an earlier draft of this change proposed skipping the retry on timeout.
     *  Prod logs (168h) showed 2 of 3 timeouts recovering through exactly this retry. */
    @Test void retriesOnTimeoutCauseChain() {
        JsonNode ok = MAPPER.readTree("{\"ok\":true}");
        StubClient client = new StubClient(n -> {
            if (n == 1) throw new RuntimeException("wrapped",
                    new java.util.concurrent.TimeoutException("Did not observe any item"));
            return ok;
        });
        assertThat(client.callTool("get_quote", null)).isSameAs(ok);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test void logsTerminalFailureWithTheToolName() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgoraClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StubClient client = new StubClient(n -> { throw new RuntimeException("stale"); });
            assertThatThrownBy(() -> client.callTool("get_form4_transactions", null))
                    .isInstanceOf(AgoraUnavailableException.class);
            assertThat(appender.list)
                    .anyMatch(e -> e.getFormattedMessage().contains("Agora unreachable for get_form4_transactions"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * A cut session recovers exactly like any other transient failure — and is reported as the
     * non-event it is: INFO, naming the tool, not "failed". Verbatim message shape from prod
     * (2026-08-07, during an Agora restart).
     */
    @Test void reportsASessionCutAtInfoAndStillRetriesOnce() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgoraClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JsonNode ok = MAPPER.readTree("{\"ok\":true}");
            StubClient client = new StubClient(n -> {
                if (n == 1) throw new RuntimeException("MCP session with server terminated");
                return ok;
            });
            assertThat(client.callTool("search_filings", null)).isSameAs(ok);
            assertThat(client.calls.get()).isEqualTo(2);
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                            && e.getFormattedMessage().contains("Agora session for search_filings was cut"));
            assertThat(appender.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** Anything that is not a cut session keeps the old WARN — a real failure must not be demoted. */
    @Test void otherTransientFailureStillWarns() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgoraClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JsonNode ok = MAPPER.readTree("{\"ok\":true}");
            StubClient client = new StubClient(n -> {
                if (n == 1) throw new RuntimeException("connection reset");
                return ok;
            });
            assertThat(client.callTool("get_quote", null)).isSameAs(ok);
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains("Agora call get_quote failed"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** The other terminal path: attempt() itself throws AgoraUnavailableException (empty response
     *  or an error envelope) and callTool rethrows it without a retry — that must log too. */
    @Test void logsTerminalFailureOnPassThrough() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgoraClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StubClient client = new StubClient(n -> { throw new AgoraUnavailableException("down"); });
            assertThatThrownBy(() -> client.callTool("get_quote", null))
                    .isInstanceOf(AgoraUnavailableException.class);
            assertThat(appender.list)
                    .anyMatch(e -> e.getFormattedMessage().contains("Agora unreachable for get_quote"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
