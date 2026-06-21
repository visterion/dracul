package de.visterion.dracul.events;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Domain-agnostic Server-Sent-Events broadcaster. Each connected emitter is tagged with the
 * email of the user who opened the stream, so events can be delivered to a single owner
 * ({@link #sendToOwner}) — multiple tabs of one user are all matched. Dead emitters are removed
 * on the next send or via the completion/timeout/error callbacks. {@link #broadcast} remains as
 * a generic broadcast-to-all seam for future global events; alert.new uses sendToOwner.
 */
@Component
public class SseBroadcaster {

    /** EventSource auto-reconnects, so a finite per-connection timeout is fine. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** An emitter together with the email of the user who opened the stream. */
    private record OwnedEmitter(String owner, SseEmitter emitter) {}

    private final List<OwnedEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(String owner) {
        OwnedEmitter owned = add(owner, new SseEmitter(TIMEOUT_MS));
        // Send a comment immediately so the HTTP response headers flush to the client before any
        // real event arrives (otherwise clients hang on connect until the first event). Real
        // connection path only — register() stays a pure registration seam for tests.
        try {
            owned.emitter().send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(owned);
        }
        return owned.emitter();
    }

    /** Package-private: register an emitter for an owner (wires removal callbacks, no I/O). */
    SseEmitter register(String owner, SseEmitter emitter) {
        return add(owner, emitter).emitter();
    }

    /** Wires the removal callbacks and stores the owned emitter; returns it so callers can
     *  remove the exact same instance on a later failure (uniform with the callback removals). */
    private OwnedEmitter add(String owner, SseEmitter emitter) {
        OwnedEmitter owned = new OwnedEmitter(owner, emitter);
        emitter.onCompletion(() -> emitters.remove(owned));
        emitter.onTimeout(() -> emitters.remove(owned));
        emitter.onError(t -> emitters.remove(owned));
        emitters.add(owned);
        return owned;
    }

    /** Sends a named event only to streams opened by the given owner. */
    public void sendToOwner(String owner, String type, Object data) {
        for (OwnedEmitter oe : emitters) {
            if (!oe.owner().equals(owner)) continue;
            try {
                oe.emitter().send(SseEmitter.event().name(type).data(data));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(oe);
            }
        }
    }

    /** Generic broadcast-to-all seam (unused by alert.new; kept for future global events). */
    public void broadcast(String type, Object data) {
        for (OwnedEmitter oe : emitters) {
            try {
                oe.emitter().send(SseEmitter.event().name(type).data(data));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(oe);
            }
        }
    }

    int subscriberCount() {
        return emitters.size();
    }
}
