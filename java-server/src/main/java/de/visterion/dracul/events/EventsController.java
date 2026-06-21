package de.visterion.dracul.events;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live event stream for the Chronicle frontend, scoped to the connecting user. The path runs
 * through Cloudflare Access (the browser EventSource GET carries the CF cookie / injected JWT),
 * so {@link CurrentUserHolder} holds the user's email at subscribe time and the emitter is
 * tagged with it — each stream receives only that user's events. v1 emits only "alert.new".
 */
@RestController
@RequestMapping("/api/events")
public class EventsController {

    private final SseBroadcaster broadcaster;

    public EventsController(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe(CurrentUserHolder.get());
    }
}
