package de.visterion.dracul.events;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Domain-agnostic Server-Sent-Events broadcaster. Holds the connected emitters
 * and fans out named events to all of them. Dead emitters are removed on the
 * next broadcast or via the completion/timeout/error callbacks. Event types
 * other than "alert.new" attach simply by calling broadcast(...).
 */
@Component
public class SseBroadcaster {

    /** EventSource auto-reconnects, so a finite per-connection timeout is fine. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = register(new SseEmitter(TIMEOUT_MS));
        // Send a comment immediately so the HTTP response headers flush to the
        // client before any real event arrives (otherwise clients hang on
        // connect until the first event). Real connection path only —
        // register() stays a pure registration seam for tests.
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /** Package-private: register an emitter (wires removal callbacks, no I/O). */
    SseEmitter register(SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(t -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    public void broadcast(String type, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(type).data(data));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    int subscriberCount() {
        return emitters.size();
    }
}
