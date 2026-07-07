package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository.OwnerItem;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerCompletionServiceTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private DaywalkerCompletionService service(DaywalkerAlertRepository alerts, TelegramNotifier notifier) {
        return new DaywalkerCompletionService(alerts, notifier, events, "CRITICAL", 3600);
    }

    @Test
    void criticalNotifiesOncePersistsAndPublishesPerOwner() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(
                new OwnerItem("u1@x.com", "wid-1", false), new OwnerItem("u2@x.com", "wid-2", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(notifier.notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis")).thenReturn(true);

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", new BigDecimal("0.9"), "run-1");

        verify(notifier, times(1)).notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis");
        verify(alerts).insert(eq("u1@x.com"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), eq(new BigDecimal("0.9")), eq("run-1"), eq(true));
        verify(alerts).insert(eq("u2@x.com"), eq("wid-2"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), eq(new BigDecimal("0.9")), eq("run-1"), eq(true));
        verify(events, times(2)).publishEvent(any(DaywalkerAlertCreatedEvent.class));
        verify(events).publishEvent(
                new DaywalkerAlertCreatedEvent("u1@x.com", "AAPL", "PRICE_SPIKE", "CRITICAL", "thesis"));
        verify(events).publishEvent(
                new DaywalkerAlertCreatedEvent("u2@x.com", "AAPL", "PRICE_SPIKE", "CRITICAL", "thesis"));
    }

    @Test
    void infoDoesNotNotifyPersistsSentFalseAndPublishesPerOwner() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "INFO",
                "thesis", null, "run-2");

        verifyNoInteractions(notifier);
        verify(alerts).insert(eq("u1@x.com"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("INFO"), eq("thesis"), isNull(), eq("run-2"), eq(false));
        verify(events, times(1)).publishEvent(
                new DaywalkerAlertCreatedEvent("u1@x.com", "AAPL", "PRICE_SPIKE", "INFO", "thesis"));
    }

    @Test
    void ownerInCooldownGetsNeitherRowNorEvent() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(
                new OwnerItem("u1@x.com", "wid-1", false), new OwnerItem("u2@x.com", "wid-2", false)));
        when(alerts.lastAlertAt("u1@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.of(Instant.now()));
        when(alerts.lastAlertAt("u2@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.empty());

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", null, "run-3");

        verify(alerts, never()).insert(eq("u1@x.com"), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(alerts).insert(eq("u2@x.com"), eq("wid-2"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), isNull(), eq("run-3"), anyBoolean());
        verify(events, times(1)).publishEvent(any(DaywalkerAlertCreatedEvent.class));
        verify(events).publishEvent(
                new DaywalkerAlertCreatedEvent("u2@x.com", "AAPL", "PRICE_SPIKE", "CRITICAL", "thesis"));
        verify(events, never()).publishEvent(
                new DaywalkerAlertCreatedEvent("u1@x.com", "AAPL", "PRICE_SPIKE", "CRITICAL", "thesis"));
    }

    @Test
    void allOwnersInCooldownNeitherNotifiesInsertsNorPublishes() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        when(alerts.lastAlertAt("u1@x.com", "AAPL", "PRICE_SPIKE")).thenReturn(Optional.of(Instant.now()));

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", null, "run-4");

        verifyNoInteractions(notifier);
        verify(alerts, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyBoolean());
        verifyNoInteractions(events);
    }

    @Test
    void unknownSymbolNeitherNotifiesInsertsNorPublishes() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("GHOST")).thenReturn(List.of());

        service(alerts, notifier).persistAssessment("GHOST", "PRICE_SPIKE", "CRITICAL",
                "thesis", null, "run-5");

        verifyNoInteractions(notifier);
        verify(alerts, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyBoolean());
        verifyNoInteractions(events);
    }
}
