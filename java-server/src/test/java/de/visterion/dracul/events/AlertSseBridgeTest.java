package de.visterion.dracul.events;

import de.visterion.dracul.daywalker.DaywalkerAlertCreatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AlertSseBridgeTest {

    @Test
    @SuppressWarnings("unchecked")
    void broadcastsAlertNewWithPayload() {
        var broadcaster = mock(SseBroadcaster.class);
        new AlertSseBridge(broadcaster).onAlertCreated(
                new DaywalkerAlertCreatedEvent("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis text"));

        var cap = ArgumentCaptor.forClass(Map.class);
        verify(broadcaster).broadcast(eq("alert.new"), cap.capture());
        Map<String, Object> p = cap.getValue();
        assertThat(p).containsEntry("symbol", "AAPL")
                .containsEntry("trigger_type", "PRICE_SPIKE")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("thesis", "thesis text");
        assertThat(p).containsKey("ts");
    }
}
