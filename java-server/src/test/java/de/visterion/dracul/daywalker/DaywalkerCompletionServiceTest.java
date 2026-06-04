package de.visterion.dracul.daywalker;

import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerCompletionServiceTest {

    private DaywalkerCompletionService service(DaywalkerAlertRepository alerts, TelegramNotifier notifier) {
        return new DaywalkerCompletionService(alerts, notifier, "CRITICAL");
    }

    @Test
    void criticalNotifiesAndPersistsSentTrue() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.resolveWatchlistItemId("default", "AAPL")).thenReturn(Optional.of("wid-1"));
        when(notifier.notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis")).thenReturn(true);

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", new BigDecimal("0.9"), "run-1");

        verify(notifier).notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis");
        verify(alerts).insert(eq("default"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), eq(new BigDecimal("0.9")), eq("run-1"), eq(true));
    }

    @Test
    void infoDoesNotNotifyAndPersistsSentFalse() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.resolveWatchlistItemId("default", "AAPL")).thenReturn(Optional.of("wid-1"));

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "INFO",
                "thesis", null, "run-2");

        verifyNoInteractions(notifier);
        verify(alerts).insert(eq("default"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("INFO"), eq("thesis"), isNull(), eq("run-2"), eq(false));
    }

    @Test
    void notifyFailureStillPersistsSentFalse() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.resolveWatchlistItemId("default", "AAPL")).thenReturn(Optional.of("wid-1"));
        when(notifier.notifyAlert(anyString(), anyString(), anyString(), any())).thenReturn(false);

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", null, "run-3");

        verify(alerts).insert(eq("default"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), isNull(), eq("run-3"), eq(false));
    }

    @Test
    void unknownSymbolNeitherNotifiesNorInserts() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.resolveWatchlistItemId("default", "GHOST")).thenReturn(Optional.empty());

        service(alerts, notifier).persistAssessment("GHOST", "PRICE_SPIKE", "CRITICAL",
                "thesis", null, "run-4");

        verifyNoInteractions(notifier);
        verify(alerts, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyBoolean());
    }
}
