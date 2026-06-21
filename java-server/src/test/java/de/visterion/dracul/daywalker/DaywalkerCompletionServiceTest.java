package de.visterion.dracul.daywalker;

import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerCompletionServiceTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private DaywalkerCompletionService service(DaywalkerAlertRepository alerts, TelegramNotifier notifier) {
        return new DaywalkerCompletionService(alerts, notifier, events, "CRITICAL");
    }

    @Test
    void criticalNotifiesPersistsSentTrueAndPublishes() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(new DaywalkerAlertRepository.OwnerItem("default", "wid-1")));
        when(notifier.notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis")).thenReturn(true);

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", new BigDecimal("0.9"), "run-1");

        verify(notifier).notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis");
        verify(alerts).insert(eq("default"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), eq(new BigDecimal("0.9")), eq("run-1"), eq(true));
        verify(events).publishEvent(
                new DaywalkerAlertCreatedEvent("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis"));
    }

    @Test
    void infoDoesNotNotifyPersistsSentFalseAndPublishes() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(new DaywalkerAlertRepository.OwnerItem("default", "wid-1")));

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "INFO",
                "thesis", null, "run-2");

        verifyNoInteractions(notifier);
        verify(alerts).insert(eq("default"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("INFO"), eq("thesis"), isNull(), eq("run-2"), eq(false));
        verify(events).publishEvent(
                new DaywalkerAlertCreatedEvent("AAPL", "PRICE_SPIKE", "INFO", "thesis"));
    }

    @Test
    void unknownSymbolNeitherNotifiesInsertsNorPublishes() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("GHOST")).thenReturn(List.of());

        service(alerts, notifier).persistAssessment("GHOST", "PRICE_SPIKE", "CRITICAL",
                "thesis", null, "run-4");

        verifyNoInteractions(notifier);
        verify(alerts, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyBoolean());
        verifyNoInteractions(events);
    }
}
