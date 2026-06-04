package de.visterion.dracul.events;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live event stream for the Chronicle frontend. Unauthenticated, consistent with
 * the rest of the read API (browser EventSource cannot send an Authorization
 * header). v1 emits only "alert.new"; the stream is generic.
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
        return broadcaster.subscribe();
    }
}
