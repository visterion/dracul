package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository.OwnerItem;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaywalkerCompletionServiceTest {

    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.6");

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private DaywalkerCompletionService service(DaywalkerAlertRepository alerts, TelegramNotifier notifier) {
        return service(alerts, notifier, null, false, DEFAULT_THRESHOLD);
    }

    private DaywalkerCompletionService service(DaywalkerAlertRepository alerts, TelegramNotifier notifier,
            VistierieClient vistierieClient, boolean escalationEnabled, BigDecimal escalationThreshold) {
        return new DaywalkerCompletionService(alerts, notifier, events, "CRITICAL", 3600,
                providerOf(vistierieClient), escalationEnabled, escalationThreshold);
    }

    /** Minimal ObjectProvider stub — only {@code getObject()} is abstract; the default
     *  {@code ifAvailable} plumbing built on top of it is exercised as-is. */
    private static ObjectProvider<VistierieClient> providerOf(VistierieClient client) {
        return new ObjectProvider<>() {
            @Override public VistierieClient getObject() { return client; }
        };
    }

    private void stubEligibleSingleOwner(DaywalkerAlertRepository alerts, String symbol, String triggerType) {
        when(alerts.findOwnersBySymbol(symbol)).thenReturn(List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(alerts.findSameUtcDay(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
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

    @Test
    void positionIdRoutesToThatOwnerPositionOnly() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(
                new OwnerItem("u1@x.com", "wid-1", true),
                new OwnerItem("u2@x.com", "wid-2", true)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                "thesis", new BigDecimal("0.9"), "run-1", "wid-2");

        verify(alerts).insert(eq("u2@x.com"), eq("wid-2"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis"), eq(new BigDecimal("0.9")), eq("run-1"), anyBoolean());
        verify(alerts, never()).insert(eq("u1@x.com"), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void nullPositionIdFansOutToWatchOnlyOwnersOnly() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(
                new OwnerItem("u1@x.com", "wid-1", true),
                new OwnerItem("u2@x.com", "wid-2", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "WARNING",
                "thesis", null, "run-2", null);

        verify(alerts).insert(eq("u2@x.com"), eq("wid-2"), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(alerts, never()).insert(eq("u1@x.com"), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void freshAlertStillInserts() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        when(alerts.lastAlertAt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(alerts.findSameUtcDay(eq("u1@x.com"), eq("AAPL"), eq("PRICE_SPIKE"), any()))
                .thenReturn(Optional.empty());

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "INFO",
                "thesis", null, "run-10");

        verify(alerts).insert(eq("u1@x.com"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("INFO"), eq("thesis"), isNull(), eq("run-10"), eq(false));
        verify(alerts, never()).updateSameDayAlert(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void sameDayDuplicateUpdatesInsteadOfInserting() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        // outside the 3600s cooldown, but on the same UTC day
        when(alerts.lastAlertAt("u1@x.com", "AAPL", "PRICE_SPIKE"))
                .thenReturn(Optional.of(Instant.now().minusSeconds(7200)));
        when(alerts.findSameUtcDay(eq("u1@x.com"), eq("AAPL"), eq("PRICE_SPIKE"), any()))
                .thenReturn(Optional.of(new DaywalkerAlertRepository.SameDayAlert("a-1", "INFO")));

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "WARNING",
                "updated thesis", new BigDecimal("0.8"), "run-11");

        verify(alerts).updateSameDayAlert("a-1", "PRICE_SPIKE", "WARNING",
                "updated thesis", new BigDecimal("0.8"), "run-11", false); // INFO -> WARNING escalates
        verify(alerts, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(events).publishEvent(new DaywalkerAlertCreatedEvent(
                "u1@x.com", "AAPL", "PRICE_SPIKE", "WARNING", "updated thesis"));
    }

    @Test
    void sameDaySeverityIsNeverLowered() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        when(alerts.findOwnersBySymbol("AAPL")).thenReturn(List.of(new OwnerItem("u1@x.com", "wid-1", false)));
        when(alerts.lastAlertAt("u1@x.com", "AAPL", "PRICE_SPIKE"))
                .thenReturn(Optional.of(Instant.now().minusSeconds(7200)));
        when(alerts.findSameUtcDay(eq("u1@x.com"), eq("AAPL"), eq("PRICE_SPIKE"), any()))
                .thenReturn(Optional.of(new DaywalkerAlertRepository.SameDayAlert("a-1", "CRITICAL")));

        service(alerts, notifier).persistAssessment("AAPL", "PRICE_SPIKE", "INFO",
                "later, calmer thesis", null, "run-12");

        // text/ts refresh, severity stays CRITICAL
        verify(alerts).updateSameDayAlert("a-1", "PRICE_SPIKE", "CRITICAL",
                "later, calmer thesis", null, "run-12", false);
    }

    // =========================================================================
    // Escalation: low-confidence CRITICAL assessments trigger daywalker-deep
    // =========================================================================

    @Test
    void criticalLowConfidenceTriggersEscalationWithContext() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var vistierie = mock(VistierieClient.class);
        stubEligibleSingleOwner(alerts, "AAPL", "PRICE_SPIKE");

        service(alerts, notifier, vistierie, true, DEFAULT_THRESHOLD)
                .persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                        "thesis text", new BigDecimal("0.4"), "run-20");

        verify(vistierie).triggerRun("daywalker-deep", Map.of(
                "symbol", "AAPL", "trigger_type", "PRICE_SPIKE", "thesis", "thesis text"));
    }

    @Test
    void criticalHighConfidenceDoesNotEscalate() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var vistierie = mock(VistierieClient.class);
        stubEligibleSingleOwner(alerts, "AAPL", "PRICE_SPIKE");

        service(alerts, notifier, vistierie, true, DEFAULT_THRESHOLD)
                .persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                        "thesis text", new BigDecimal("0.9"), "run-21");

        verify(vistierie, never()).triggerRun(anyString(), any());
    }

    @Test
    void warningLowConfidenceDoesNotEscalate() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var vistierie = mock(VistierieClient.class);
        stubEligibleSingleOwner(alerts, "AAPL", "PRICE_SPIKE");

        service(alerts, notifier, vistierie, true, DEFAULT_THRESHOLD)
                .persistAssessment("AAPL", "PRICE_SPIKE", "WARNING",
                        "thesis text", new BigDecimal("0.4"), "run-22");

        verify(vistierie, never()).triggerRun(anyString(), any());
    }

    @Test
    void fromEscalationNeverReTriggers() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var vistierie = mock(VistierieClient.class);
        stubEligibleSingleOwner(alerts, "AAPL", "PRICE_SPIKE");

        service(alerts, notifier, vistierie, true, DEFAULT_THRESHOLD)
                .persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                        "thesis text", new BigDecimal("0.4"), "run-23", null, true);

        verify(vistierie, never()).triggerRun(anyString(), any());
    }

    @Test
    void escalationDisabledNeverTriggers() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var vistierie = mock(VistierieClient.class);
        stubEligibleSingleOwner(alerts, "AAPL", "PRICE_SPIKE");

        service(alerts, notifier, vistierie, false, DEFAULT_THRESHOLD)
                .persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                        "thesis text", new BigDecimal("0.4"), "run-24");

        verify(vistierie, never()).triggerRun(anyString(), any());
    }

    @Test
    void escalationTriggerExceptionDoesNotAffectAlertFlow() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        var vistierie = mock(VistierieClient.class);
        stubEligibleSingleOwner(alerts, "AAPL", "PRICE_SPIKE");
        when(notifier.notifyAlert("AAPL", "PRICE_SPIKE", "CRITICAL", "thesis text")).thenReturn(true);
        when(vistierie.triggerRun(anyString(), any())).thenThrow(new RuntimeException("vistierie down"));

        service(alerts, notifier, vistierie, true, DEFAULT_THRESHOLD)
                .persistAssessment("AAPL", "PRICE_SPIKE", "CRITICAL",
                        "thesis text", new BigDecimal("0.4"), "run-25");

        verify(alerts).insert(eq("u1@x.com"), eq("wid-1"), eq("AAPL"), eq("PRICE_SPIKE"),
                eq("CRITICAL"), eq("thesis text"), eq(new BigDecimal("0.4")), eq("run-25"), eq(true));
        verify(events, times(1)).publishEvent(any(DaywalkerAlertCreatedEvent.class));
    }

    @Test
    void noVistierieClientAvailableSkipsEscalationSilently() {
        var alerts = mock(DaywalkerAlertRepository.class);
        var notifier = mock(TelegramNotifier.class);
        stubEligibleSingleOwner(alerts, "AAPL", "PRICE_SPIKE");

        // escalation enabled, but the ObjectProvider resolves to no bean (null) — must not throw.
        assertThatCode(() -> service(alerts, notifier, null, true, DEFAULT_THRESHOLD)
                .persistAssessment("AAPL", "PRICE_SPIKE",
                        "CRITICAL", "thesis text", new BigDecimal("0.4"), "run-26"))
                .doesNotThrowAnyException();
    }
}
