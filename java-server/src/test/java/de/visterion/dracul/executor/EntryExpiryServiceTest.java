package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Verifies {@link EntryExpiryService}: cancels unfilled GTD entries past their expiry —
 *  never re-prices — mirroring {@link HardTriggerServiceTest}'s gateway/repo-mocking idiom. */
class EntryExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private EntryExpiryService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.3");
        service = new EntryExpiryService(gateway, positionRepo, legRepo, signalRepo, decisionRepo,
                ruleVersions, mapper, clock);
    }

    private ExecutorPosition openPosition(long id, String symbol, String sourceSignalId) {
        return new ExecutorPosition(id, "c", symbol, "BUY", BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), 1, null, List.of(), sourceSignalId,
                "agent", "2026-07-01", null, "OPEN", "brk-1", null, null, 0, null, null, null,
                null, "stop-1", null, null, null, null, 0, null, "2026-07-06T00:00:00Z",
                null, null, null, null, false);
    }

    @Test
    void workingUnfilledEntry_cancelsFullyAndMarksSignalExpired() {
        ExecutorPosition p = openPosition(1L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.WORKING, BigDecimal.TEN, BigDecimal.ZERO, null, null));

        Set<Long> cancelled = service.expire("c", "run1");

        assertThat(cancelled).containsExactly(1L);
        assertThat(gateway.cancelledOrderIds).containsExactly("brk-1");
        verify(positionRepo).markCancelled(1L);
        verify(positionRepo).clearEntryExpiry(1L);
        verify(signalRepo).markStatus("sig-1", "EXPIRED");

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("CANCEL_EXPIRED");
        assertThat(log.reasonCode()).isEqualTo("SIGNAL_EXPIRED");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.signalId()).isEqualTo("sig-1");
        assertThat(log.orderJson().get("partial").asBoolean()).isFalse();
    }

    @Test
    void workingUnfilledEntry_noSourceSignal_skipsSignalMarkButStillCancels() {
        ExecutorPosition p = openPosition(2L, "ACME", null);
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.WORKING, BigDecimal.TEN, BigDecimal.ZERO, null, null));

        service.expire("c", "run1");

        verify(positionRepo).markCancelled(2L);
        verify(signalRepo, never()).markStatus(any(), any());
    }

    @Test
    void workingUnfilledEntry_cancelledLegsAreNotLeftOpen() {
        // The entry never filled, so no share was ever held on this position. Any OPEN leg row
        // would keep claiming shares against a CANCELLED position -- and CANCELLED, not CLOSED:
        // nothing exited, so there is no exit price to book.
        ExecutorPosition p = openPosition(1L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.WORKING, BigDecimal.TEN, BigDecimal.ZERO, null, null));

        service.expire("c", "run1");

        // verifyNoMoreInteractions rather than a never() on closeLeg: this service never calls
        // closeLeg on any path, so pinning that one method could not fail. What CAN fail is any
        // OTHER write to the leg table appearing here -- an exit price on a position that never
        // held a share would invent a trade that did not happen.
        verify(legRepo).cancelOpenLegs(1L);
        verifyNoMoreInteractions(legRepo);
    }

    @Test
    void partiallyFilledEntry_keepsItsLegsOpen() {
        // The opposite case: part of the entry DID fill, the position stays OPEN and the broker
        // holds those shares. Cancelling the legs here would drop a real holding out of the book.
        ExecutorPosition p = openPosition(3L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.PARTIALLY_FILLED, BigDecimal.TEN, new BigDecimal("4"),
                new BigDecimal("100"), null));

        service.expire("c", "run1");

        verify(legRepo, never()).cancelOpenLegs(anyLong());
    }

    @Test
    void partiallyFilledEntry_cancelsRemainderKeepsPositionOpen() {
        ExecutorPosition p = openPosition(3L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.PARTIALLY_FILLED, BigDecimal.TEN, new BigDecimal("4"), new BigDecimal("100"), null));

        Set<Long> cancelled = service.expire("c", "run1");

        assertThat(cancelled).isEmpty(); // partial: position stays OPEN, not a full cancel
        assertThat(gateway.cancelledOrderIds).containsExactly("brk-1");
        verify(positionRepo, never()).markCancelled(3L);
        verify(positionRepo).clearEntryExpiry(3L);
        verify(signalRepo, never()).markStatus(any(), any());

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();
        assertThat(log.action()).isEqualTo("CANCEL_EXPIRED");
        assertThat(log.reasonCode()).isEqualTo("SIGNAL_EXPIRED");
        assertThat(log.orderJson().get("partial").asBoolean()).isTrue();
    }

    @Test
    void filledEntry_doesNothing() {
        ExecutorPosition p = openPosition(4L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("100"), null));

        service.expire("c", "run1");

        assertThat(gateway.cancelledOrderIds).isEmpty();
        verify(positionRepo, never()).markCancelled(4L);
        verify(signalRepo, never()).markStatus(any(), any());
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void statusUnavailable_orderNotFound_doesNothing() {
        ExecutorPosition p = openPosition(5L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        // no matching order seeded

        service.expire("c", "run1");

        assertThat(gateway.cancelledOrderIds).isEmpty();
        verify(positionRepo, never()).markCancelled(5L);
        verify(signalRepo, never()).markStatus(any(), any());
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void brokerUnavailableOnOrdersFetch_escalatesBookUntouched() {
        ExecutorPosition p = openPosition(6L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.unavailable = true;

        Set<Long> cancelled = service.expire("c", "run1");

        assertThat(cancelled).isEmpty();
        verify(positionRepo, never()).markCancelled(6L);
        verify(signalRepo, never()).markStatus(any(), any());

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.symbol()).isEqualTo("ACME");
    }

    @Test
    void wrongConnection_isFilteredOutBeforeGatewayCall() {
        ExecutorPosition other = new ExecutorPosition(7L, "other-conn", "ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null, List.of(),
                "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-1", null, null, 0, null, null,
                null, null, "stop-1", null, null, null, null, 0, null, "2026-07-06T00:00:00Z",
                null, null, null, null, false);
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(other));

        service.expire("c", "run1");

        assertThat(gateway.cancelledOrderIds).isEmpty();
        verify(positionRepo, never()).markCancelled(7L);
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void partiallyFilled_secondRunDoesNothing_expiryIsOneShot() {
        // First run cancels the remainder and clears entry_expires_at; the second run's expiry
        // query (entry_expires_at IS NOT NULL) therefore no longer returns the row — even though
        // the broker keeps reporting the order as PARTIALLY_FILLED.
        ExecutorPosition p = openPosition(8L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW))
                .thenReturn(List.of(p))
                .thenReturn(List.of());
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.PARTIALLY_FILLED, BigDecimal.TEN, new BigDecimal("4"), new BigDecimal("100"), null));

        service.expire("c", "run1");
        service.expire("c", "run2");

        assertThat(gateway.cancelledOrderIds).containsExactly("brk-1"); // exactly once
        verify(positionRepo, times(1)).clearEntryExpiry(8L);
        verify(decisionRepo, times(1)).insert(any());
    }

    // -------------------------------------------------------------------
    // A rejection is a verdict, not an outage. BrokerRejectedException extends
    // BrokerUnavailableException, so every catch here used to file the broker's explicit "no"
    // as BROKER_UNAVAILABLE -- the one thing this branch defines that code as never meaning.
    // -------------------------------------------------------------------

    @Test
    void aRejectedCancel_isFiledAsBrokerRejectedNotAsAnOutage() {
        ExecutorPosition p = openPosition(1L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("brk-1", "sig-1", "ACME", OrderRole.ENTRY,
                OrderStatus.WORKING, BigDecimal.TEN, BigDecimal.ZERO, null, null));
        // The order read succeeds; only the cancel is rejected.
        gateway.rejectCancelWith = new BrokerRejectedException(
                "agora order rejected [ORDER_ALREADY_FILLED]: nothing to cancel",
                "ORDER_ALREADY_FILLED", List.of());

        Set<Long> cancelled = service.expire("c", "run1");

        assertThat(cancelled).isEmpty();
        verify(positionRepo, never()).markCancelled(anyLong());

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.reasonCode()).isNotEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.inputsSnapshot().path("reject_code").asString())
                .isEqualTo("ORDER_ALREADY_FILLED");
        assertThat(log.reasoning()).contains("[ORDER_ALREADY_FILLED]");
    }

    @Test
    void aRejectedOrderRead_isFiledAsBrokerRejectedNotAsAnOutage() {
        ExecutorPosition p = openPosition(1L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.rejectReadsWith = new BrokerRejectedException(
                "agora order rejected [UNKNOWN_CONNECTION]: no such connection",
                "UNKNOWN_CONNECTION", List.of());

        service.expire("c", "run1");

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().reasonCode()).isEqualTo("BROKER_REJECTED");
    }

    @Test
    void aRealOutage_stillFilesAsBrokerUnavailable() {
        // The negative half: the split must not swallow the case the code is actually for.
        ExecutorPosition p = openPosition(1L, "ACME", "sig-1");
        when(positionRepo.findOpenUnfilledPastExpiry(NOW)).thenReturn(List.of(p));
        gateway.unavailable = true;

        service.expire("c", "run1");

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
    }
}
