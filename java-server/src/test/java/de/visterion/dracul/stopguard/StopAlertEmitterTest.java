package de.visterion.dracul.stopguard;

import de.visterion.dracul.daywalker.DaywalkerAlertCreatedEvent;
import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StopAlertEmitterTest {

    private final DaywalkerAlertRepository alerts = mock(DaywalkerAlertRepository.class);
    private final TelegramNotifier notifier = mock(TelegramNotifier.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    // notify-level WARNING, cooldown 23h
    private final StopAlertEmitter emitter =
            new StopAlertEmitter(alerts, notifier, events, "WARNING", 82800);

    private static final Instant NOW = Instant.parse("2026-06-24T14:30:00Z");

    @Test
    void breachedEmitsCriticalAlertAndNotifies() {
        when(alerts.lastAlertAt(any(), any(), any())).thenReturn(Optional.empty());
        when(notifier.notifyAlert(any(), any(), any(), any())).thenReturn(true);

        boolean emitted = emitter.emit("alice", "item-1", "AAA", StopZone.BREACHED,
                new BigDecimal("96.00"), new BigDecimal("100.00"), NOW);

        assertThat(emitted).isTrue();
        verify(alerts).insert(eq("alice"), eq("item-1"), eq("AAA"),
                eq("STOP_BREACHED"), eq("CRITICAL"), contains("Stop gerissen"),
                isNull(), startsWith("stopguard-"), eq(true));
        verify(notifier).notifyAlert(eq("AAA"), eq("STOP_BREACHED"), eq("CRITICAL"), anyString());
        verify(events).publishEvent(any(DaywalkerAlertCreatedEvent.class));
    }

    @Test
    void proximityEmitsWarningAndNotifiesAtWarningLevel() {
        when(alerts.lastAlertAt(any(), any(), any())).thenReturn(Optional.empty());
        when(notifier.notifyAlert(any(), any(), any(), any())).thenReturn(true);

        boolean emitted = emitter.emit("alice", "item-1", "AAA", StopZone.PROXIMITY,
                new BigDecimal("103.00"), new BigDecimal("100.00"), NOW);

        assertThat(emitted).isTrue();
        verify(alerts).insert(eq("alice"), eq("item-1"), eq("AAA"),
                eq("STOP_PROXIMITY"), eq("WARNING"), anyString(),
                isNull(), anyString(), eq(true));
    }

    @Test
    void withinCooldownDoesNotEmit() {
        when(alerts.lastAlertAt("alice", "AAA", "STOP_PROXIMITY"))
                .thenReturn(Optional.of(NOW.minusSeconds(3600)));   // 1h ago < 23h cooldown

        boolean emitted = emitter.emit("alice", "item-1", "AAA", StopZone.PROXIMITY,
                new BigDecimal("103.00"), new BigDecimal("100.00"), NOW);

        assertThat(emitted).isFalse();
        verifyNoInteractions(notifier);
        verify(alerts, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void proximityAndBreachedHaveIndependentCooldowns() {
        // proximity in cooldown, breach is not -> breach still emits
        when(alerts.lastAlertAt("alice", "AAA", "STOP_PROXIMITY"))
                .thenReturn(Optional.of(NOW.minusSeconds(60)));
        when(alerts.lastAlertAt("alice", "AAA", "STOP_BREACHED"))
                .thenReturn(Optional.empty());
        when(notifier.notifyAlert(any(), any(), any(), any())).thenReturn(true);

        boolean emitted = emitter.emit("alice", "item-1", "AAA", StopZone.BREACHED,
                new BigDecimal("96.00"), new BigDecimal("100.00"), NOW);

        assertThat(emitted).isTrue();
        verify(alerts).insert(any(), any(), any(), eq("STOP_BREACHED"),
                any(), any(), any(), any(), anyBoolean());
    }
}
