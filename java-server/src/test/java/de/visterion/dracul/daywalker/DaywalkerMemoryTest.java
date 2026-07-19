package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository.OwnerItem;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 9 write-back-hook coverage for the daywalker-alert seam (spec §6, cell-only append -- no
 * research_memory_link row). A fresh alert insert (the {@code else} branch of {@code
 * persistAssessment}'s {@code existing.isPresent()} check) triggers one
 * writeThesisMemory("daywalker_alert", ...) call per newly-inserted owner row; the same-day-update
 * branch does NOT (append-only, no cell on a mere severity/text refresh).
 */
class DaywalkerMemoryTest {

    private static final String PRIMARY_USER = "primary@x.com";
    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.6");

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final de.visterion.dracul.position.HeldPositionService heldPositions =
            mock(de.visterion.dracul.position.HeldPositionService.class);

    private static ObjectProvider<VistierieClient> providerOf(VistierieClient client) {
        return new ObjectProvider<>() {
            @Override public VistierieClient getObject() { return client; }
        };
    }

    private DaywalkerCompletionService service(DaywalkerAlertRepository alerts, TelegramNotifier notifier,
            HiveMemResearchService memory) {
        return new DaywalkerCompletionService(alerts, notifier, events, "CRITICAL", 3600,
                providerOf(null), false, true, DEFAULT_THRESHOLD,
                PRIMARY_USER, "http://localhost:8080", "deep-tkn", heldPositions, "depot-1", memory);
    }

    @Test
    void freshAlertInsert_writesOneMemoryCellPerNewOwnerRow() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var memory = mock(HiveMemResearchService.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(
                new OwnerItem("u1@x.com", "wid-1", false), new OwnerItem("u2@x.com", "wid-2", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(alerts.findSameUtcDay(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(notifier.notifyAlert(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        service(alerts, notifier, memory).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", new BigDecimal("0.9"), "run-1");

        verify(memory, times(2)).writeThesisMemory(eq("daywalker_alert"), eq("AAPL"),
                eq("PRICE_SPIKE"), eq("thesis"), any(), any(), any(), any(), any(), anyDouble(), eq("run-1"));
    }

    @Test
    void sameDayUpdate_doesNotWriteMemoryCell() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var memory = mock(HiveMemResearchService.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(
                List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var existing = new DaywalkerAlertRepository.SameDayAlert("alert-1", "WARNING");
        when(alerts.findSameUtcDay(eq("u1@x.com"), eq("AAPL"), eq("PRICE_SPIKE"), any()))
                .thenReturn(Optional.of(existing));
        when(notifier.notifyAlert(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        service(alerts, notifier, memory).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "refreshed thesis", new BigDecimal("0.9"), "run-2");

        verify(alerts, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(alerts).updateSameDayAlert(eq("alert-1"), eq("PRICE_SPIKE"), eq("CRITICAL"),
                eq("refreshed thesis"), eq(new BigDecimal("0.9")), eq("run-2"), eq(true), isNull());
        verifyNoInteractions(memory);
    }

    @Test
    void memoryThrows_persistAssessmentDoesNotPropagate() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var memory = mock(HiveMemResearchService.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(
                List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(alerts.findSameUtcDay(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(notifier.notifyAlert(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("bug")).when(memory).writeThesisMemory(anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), anyDouble(), any());

        service(alerts, notifier, memory).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", new BigDecimal("0.9"), "run-3");

        verify(alerts, times(1)).insert(eq("u1@x.com"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), eq(new BigDecimal("0.9")), eq("run-3"), eq(true), isNull());
    }
}
