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
    void sendToOwnerDeliversOnlyToMatchingOwner() {
        var broadcaster = new SseBroadcaster();
        var a = new CapturingEmitter(false);
        var b = new CapturingEmitter(false);
        broadcaster.register("a@x.com", a);
        broadcaster.register("b@x.com", b);

        broadcaster.sendToOwner("a@x.com", "alert.new", Map.of("symbol", "AAPL"));

        assertThat(a.sends).isEqualTo(1);
        assertThat(b.sends).isEqualTo(0);
    }

    @Test
    void sendToOwnerDeliversToAllTabsOfSameOwner() {
        var broadcaster = new SseBroadcaster();
        var tab1 = new CapturingEmitter(false);
        var tab2 = new CapturingEmitter(false);
        broadcaster.register("a@x.com", tab1);
        broadcaster.register("a@x.com", tab2);

        broadcaster.sendToOwner("a@x.com", "alert.new", Map.of("symbol", "AAPL"));

        assertThat(tab1.sends).isEqualTo(1);
        assertThat(tab2.sends).isEqualTo(1);
    }

    @Test
    void sendToOwnerDropsDeadEmitter() {
        var broadcaster = new SseBroadcaster();
        var dead = new CapturingEmitter(true);
        broadcaster.register("a@x.com", dead);
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);

        broadcaster.sendToOwner("a@x.com", "alert.new", Map.of("symbol", "AAPL"));

        assertThat(broadcaster.subscriberCount()).isEqualTo(0);
    }

    @Test
    void subscribeRegistersWithOwnerAndReturnsEmitter() {
        var broadcaster = new SseBroadcaster();
        SseEmitter emitter = broadcaster.subscribe("a@x.com");
        assertThat(emitter).isNotNull();
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
    }

    @Test
    void broadcastDeliversToAllAndDropsDead() {
        var broadcaster = new SseBroadcaster();
        var live = new CapturingEmitter(false);
        var dead = new CapturingEmitter(true);
        broadcaster.register("a@x.com", live);
        broadcaster.register("b@x.com", dead);

        broadcaster.broadcast("alert.new", Map.of("symbol", "AAPL"));

        assertThat(live.sends).isEqualTo(1);
        assertThat(broadcaster.subscriberCount()).isEqualTo(1); // dead emitter removed
    }
}
