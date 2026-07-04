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
            super("http://unused", "", 8000);
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
}
