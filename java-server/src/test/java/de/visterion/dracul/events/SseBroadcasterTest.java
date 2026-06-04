package de.visterion.dracul.events;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SseBroadcasterTest {

    /** Captures send() calls without a servlet container; optionally simulates a dead client. */
    static class CapturingEmitter extends SseEmitter {
        int sends = 0;
        final boolean fail;
        CapturingEmitter(boolean fail) { this.fail = fail; }
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (fail) throw new IOException("dead client");
            sends++;
        }
    }

    @Test
    void deliversToLiveSubscriberAndDropsDeadOnes() {
        var broadcaster = new SseBroadcaster();
        var live = new CapturingEmitter(false);
        var dead = new CapturingEmitter(true);
        broadcaster.register(live);
        broadcaster.register(dead);
        assertThat(broadcaster.subscriberCount()).isEqualTo(2);

        broadcaster.broadcast("alert.new", Map.of("symbol", "AAPL"));

        assertThat(live.sends).isEqualTo(1);
        assertThat(broadcaster.subscriberCount()).isEqualTo(1); // dead emitter removed by broadcast
    }

    @Test
    void subscribeRegistersAndReturnsEmitter() {
        var broadcaster = new SseBroadcaster();
        SseEmitter emitter = broadcaster.subscribe();
        assertThat(emitter).isNotNull();
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
    }
}
