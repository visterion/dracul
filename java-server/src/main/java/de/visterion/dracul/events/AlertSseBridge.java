package de.visterion.dracul.events;

import de.visterion.dracul.daywalker.DaywalkerAlertCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bridges Daywalker domain events onto the generic SSE stream as "alert.new". */
@Component
public class AlertSseBridge {

    private final SseBroadcaster broadcaster;

    public AlertSseBridge(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    // Runs synchronously on the webhook thread (default Spring @EventListener).
    // Keep this body total and exception-free — sendToOwner() already swallows
    // per-emitter I/O errors — so it can never break the /complete 204 contract.
    // Future verdict.new / strigoi.status bridges should preserve that property.
    @EventListener
    public void onAlertCreated(DaywalkerAlertCreatedEvent e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", e.symbol());
        payload.put("trigger_type", e.triggerType());
        payload.put("severity", e.severity());
        payload.put("thesis", e.thesis());
        payload.put("ts", Instant.now().toString());
        broadcaster.sendToOwner(e.owner(), "alert.new", payload);
    }
}
