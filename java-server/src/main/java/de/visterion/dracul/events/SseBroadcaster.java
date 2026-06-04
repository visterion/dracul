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
        return register(new SseEmitter(TIMEOUT_MS));
    }

    /** Package-private: register a (possibly test-supplied) emitter. */
    SseEmitter register(SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(t -> emitters.remove(emitter));
        emitters.add(emitter);
        // Send a comment immediately so that HTTP response headers are flushed
        // to the client before any real event arrives.
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
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
