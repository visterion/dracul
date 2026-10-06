package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerClosedPosition;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 2026-10-03 §5.1/§9: every ReconcileService path that REBUILDS an {@link ExecutorPosition}
 * (withTrim, the RECONCILE_GONE {@code effective} copy in resolveExit, withQty, the
 * ENTRY_PRICE_SYNC copy and the updateMaintenance return) must forward the five V52 fields and
 * the V53 rebalance_exit_at. A
 * dropped forward is not a one-pass glitch: a CONVICTION row read back as STANDARD gets the
 * chandelier for that pass and the monotonic ratchet guard makes it permanent (R1 Minor 8).
 * Every field is set to a distinct NON-default value so a dropped OR swapped forward fails.
 */
class ExecutorPositionCopySiteContractTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final String REASON = "synthetic thesis-destroying event";
    private static final String FLAGGED_AT = "2026-07-07 22:30:00+00";
    private static final String PENDING_TRIM = "trim-ord-1";
    private static final String REBALANCE_AT = "2026-07-07 22:40:00+00";

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final CooldownRepository cooldownRepo = mock(CooldownRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final TelegramNotifier telegram = mock(TelegramNotifier.class);
    private final ExecutorNotifier executorNotifier = mock(ExecutorNotifier.class);

    private ReconcileService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-test");
        when(decisionRepo.trimSubmission("trim-ord-1")).thenReturn(
                new DecisionLogRepository.TrimSubmission(NOW, "run-prev", new BigDecimal("5")));
        service = new ReconcileService(gateway, positionRepo, decisionRepo, cooldownRepo,
                ruleVersions, new ObjectMapper(), telegram, executorNotifier, 10, 24, legRepo,
                new BigDecimal("0.50"), ConvictionProfile.defaults(), new InMemorySavingsPlanRepository(),
                new SavingsPlanAudit(decisionRepo, ruleVersions, new ObjectMapper(), telegram),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExecutorPosition marked(ExecutorPosition p) {
        return ExecutorPositionFixtures.withRebalanceExitAt(
                ExecutorPositionFixtures.withProfileFields(p, ExitProfile.CONVICTION, REASON,
                        FLAGGED_AT, PENDING_TRIM, true),
                REBALANCE_AT);
    }

    private static void assertCarried(ExecutorPosition p) {
        assertThat(p.exitProfile()).isEqualTo(ExitProfile.CONVICTION);
        assertThat(p.catastropheReason()).isEqualTo(REASON);
        assertThat(p.catastropheFlaggedAt()).isEqualTo(FLAGGED_AT);
        assertThat(p.pendingTrimOrderId()).isEqualTo(PENDING_TRIM);
        assertThat(p.brokerStopNarrow()).isTrue();
        // V53 (spec 2026-10-04 §4): a dropped forward would make a committed rebalance exit
        // vanish for one pass — the hard trigger would not flatten it and capacity would count it.
        assertThat(p.rebalanceExitAt()).isEqualTo(REBALANCE_AT);
    }

    /** A filled BUY at 100 with logical stop 95 and a leg resting at 93. */
    private static ExecutorPosition position(long id, BigDecimal qty, int tranche,
            String brokerOrderId, String stopOrderId, String tranche2OrderId,
            String tranche2StopOrderId) {
        return ExecutorPositionFixtures.withoutKillLevel(id, "c", "SYNK", "BUY", qty,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), tranche, null,
                List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN", brokerOrderId, null, null,
                0, null, null, null, null, stopOrderId, null, null, tranche2OrderId,
                tranche2StopOrderId, 0, null, null, null, null, null, null, false,
                new BigDecimal("93.00"), "2026-07-01T09:00:00Z");
    }

    private static ExecutorPositionLeg leg(long id, long positionId, int tranche,
            String entryOrderId, String stopOrderId, BigDecimal qty) {
        return new ExecutorPositionLeg(id, positionId, tranche, entryOrderId, stopOrderId, qty,
                ExecutorPositionLeg.OPEN, null, null, null);
    }

    private static BrokerOrder filled(String orderId, BigDecimal qty, BigDecimal price) {
        return new BrokerOrder(orderId, "ref-" + orderId, "SYNK", OrderRole.OTHER,
                OrderStatus.FILLED, qty, qty, price, null);
    }

    private static BigDecimal comparesTo(String expected) {
        BigDecimal e = new BigDecimal(expected);
        return org.mockito.ArgumentMatchers.argThat(
                (BigDecimal actual) -> actual != null && actual.compareTo(e) == 0);
    }

    /** ENTRY_PRICE_SYNC rebuild + updateMaintenance return, one pass. */
    @Test
    void entryPriceSyncAndMaintenanceRebuildsCarryTheV52Fields() {
        ExecutorPosition p = marked(position(70L, BigDecimal.TEN, 1, "brk-70", "stop-70", null, null));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("SYNK", "BUY", BigDecimal.TEN,
                new BigDecimal("99.50"), new BigDecimal("101"), 1));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).syncEntryPrice(70L, new BigDecimal("99.500000"));
        assertThat(survivors).singleElement()
                .satisfies(ExecutorPositionCopySiteContractTest::assertCarried);
    }

    /** withTrim rebuild (one of two legs filled). */
    @Test
    void trimRebuildCarriesTheV52Fields() {
        ExecutorPosition p = marked(position(71L, new BigDecimal("20"), 2, "ord-1", "stop-1",
                "ord-2", "stop-2"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(71L)).thenReturn(List.of(
                leg(20L, 71L, 1, "ord-1", "stop-1", BigDecimal.TEN),
                leg(21L, 71L, 2, "ord-2", "stop-2", BigDecimal.TEN)));
        gateway.seedPosition(new BrokerPosition("SYNK", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", BigDecimal.TEN, new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).recordTrim(eq(71L), comparesTo("10"), eq(1));
        assertThat(survivors).singleElement()
                .satisfies(ExecutorPositionCopySiteContractTest::assertCarried);
    }

    /** withQty rebuild (QTY_SYNC on a legless row whose tranche-2 limit never filled). */
    @Test
    void qtySyncRebuildCarriesTheV52Fields() {
        ExecutorPosition p = marked(position(72L, new BigDecimal("12"), 2, "brk-72", "stop-72",
                "t2-72", "t2stop-72"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("SYNK", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).syncQty(72L, new BigDecimal("6"));
        assertThat(survivors).singleElement()
                .satisfies(ExecutorPositionCopySiteContractTest::assertCarried);
    }

    /** resolveExit's RECONCILE_GONE close-match rebuild ({@code effective}, booked + notified). */
    @Test
    void reconcileGoneCloseMatchRebuildCarriesTheV52Fields() {
        ExecutorPosition p = marked(position(73L, BigDecimal.TEN, 1, "brk-73", "stop-73", null, null));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedClosedPosition(new BrokerClosedPosition("SYNK", new BigDecimal("61.78"),
                new BigDecimal("61.53"), new BigDecimal("-0.25"), "sig-1"));

        service.reconcile("c", "run1");

        verify(positionRepo).syncEntryPrice(73L, new BigDecimal("61.78"));
        ArgumentCaptor<ExecutorPosition> booked = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(executorNotifier).notifyExit(booked.capture(), eq("RECONCILE_GONE"), any(), any(), any());
        assertCarried(booked.getValue());
    }
}
