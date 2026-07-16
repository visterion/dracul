package de.visterion.dracul.executor;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final CooldownRepository cooldownRepo = mock(CooldownRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TelegramNotifier telegram = mock(TelegramNotifier.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ReconcileService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new ReconcileService(gateway, positionRepo, decisionRepo, cooldownRepo,
                ruleVersions, mapper, telegram, 10, 24, clock);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, String brokerOrderId, String stopOrderId,
            BigDecimal highest, BigDecimal mfeR) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, entry, initialStop,
                initialStop, 1, null, List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN",
                brokerOrderId, highest, mfeR, 0, null, null, null, null, stopOrderId,
                null, null, null, null, 0, null, null, null, null, null, null);
    }

    @Test
    void stopLegFilled_closesWithHardStopAndRealizedR() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-1", "stop-1", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("stop-1", "ref-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("95"), "brk-1"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        ArgumentCaptor<BigDecimal> exitPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(1L), exitPriceCaptor.capture(), realizedRCaptor.capture(),
                eq("HARD_STOP"));
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("95");
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("-1.0");

        ArgumentCaptor<Instant> expiryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cooldownRepo).add(eq("ACME"), eq("HARD_STOP"), expiryCaptor.capture(), any());
        assertThat(expiryCaptor.getValue()).isEqualTo(NOW.plus(java.time.Duration.ofDays(10)));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("LOG_HARD_EXIT");
        assertThat(log.reasonCode()).isEqualTo("HARD_STOP");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.ruleVersion()).isEqualTo("exec-v0.2");

        assertThat(survivors).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any());
    }

    private ExecutorPosition pendingExitPosition(long id, String symbol, BigDecimal entry,
            BigDecimal initialStop, String stopOrderId, String pendingExitReason,
            String exitOrderId, BigDecimal pendingExitFillPrice) {
        return new ExecutorPosition(id, "c", symbol, "BUY", BigDecimal.TEN, entry, initialStop,
                initialStop, 1, null, List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN",
                "brk-" + id, null, null, 0, null, null, null, null, stopOrderId,
                null, null, null, null, 0, null, null, null,
                pendingExitReason, exitOrderId, pendingExitFillPrice);
    }

    @Test
    void reconcileDoesNotCloseWhileBrokerStillHoldsPosition() {
        // Verified prod incident (PSMT 2026-07-13): a hard trigger already flattened and stamped
        // a pending exit, but the broker still reports the position held (5 shares) with a
        // working SELL exit order. Closing here would be the exact incident: wrong exit
        // price/R and a mismatched book vs. broker state. Must survive untouched instead.
        ExecutorPosition p = pendingExitPosition(30L, "PSMT", new BigDecimal("193.87"),
                new BigDecimal("190"), "stop-30", "HARD_STOP", "close-30", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("PSMT", "BUY", new BigDecimal("5"),
                new BigDecimal("193.87"), new BigDecimal("180"), null));
        gateway.seedOrder(new BrokerOrder("close-30", "ref-30", "PSMT", OrderRole.OTHER,
                OrderStatus.WORKING, new BigDecimal("5"), BigDecimal.ZERO, null, null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("ORPHAN_POSITION"));

        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.id()).isEqualTo(30L);
        assertThat(survivor.status()).isEqualTo("OPEN");
        assertThat(survivor.pendingExitReason()).isEqualTo("HARD_STOP");
    }

    @Test
    void pendingExitStale_beyondThreshold_escalatesOnceAndSurvives() {
        // Spec §4.3 (a4-netpositions-first-design): a pending exit that never confirms
        // escalates via the existing CRITICAL path (decision log + Telegram); no auto-retry,
        // no auto-close. Threshold is 24h (test default); this row was submitted 25h ago.
        ExecutorPosition p = pendingExitPosition(40L, "STALE1", new BigDecimal("100"),
                new BigDecimal("95"), "stop-40", "HARD_STOP", "close-40", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(positionRepo.exitSubmittedAt(40L)).thenReturn(NOW.minus(java.time.Duration.ofHours(25)));
        when(decisionRepo.countBySymbolAndReasonCode("STALE1", "PENDING_EXIT_STALE")).thenReturn(0);

        gateway.seedPosition(new BrokerPosition("STALE1", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).status()).isEqualTo("OPEN");
        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("PENDING_EXIT_STALE");
        assertThat(log.symbol()).isEqualTo("STALE1");
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(40L);
        assertThat(log.reasoning()).contains("STALE1").contains("25");

        verify(telegram).notifyAlert(eq("STALE1"), eq("PENDING_EXIT_STALE"), eq("CRITICAL"), any());
    }

    @Test
    void pendingExitStale_alreadyEscalated_doesNotEscalateAgain() {
        ExecutorPosition p = pendingExitPosition(41L, "STALE2", new BigDecimal("100"),
                new BigDecimal("95"), "stop-41", "HARD_STOP", "close-41", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(positionRepo.exitSubmittedAt(41L)).thenReturn(NOW.minus(java.time.Duration.ofHours(48)));
        when(decisionRepo.countBySymbolAndReasonCode("STALE2", "PENDING_EXIT_STALE")).thenReturn(1);

        gateway.seedPosition(new BrokerPosition("STALE2", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(survivors).hasSize(1);
        verify(decisionRepo, never()).insert(any());
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
    }

    @Test
    void pendingExitStale_belowThreshold_doesNotEscalate() {
        ExecutorPosition p = pendingExitPosition(42L, "FRESH1", new BigDecimal("100"),
                new BigDecimal("95"), "stop-42", "HARD_STOP", "close-42", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(positionRepo.exitSubmittedAt(42L)).thenReturn(NOW.minus(java.time.Duration.ofHours(1)));

        gateway.seedPosition(new BrokerPosition("FRESH1", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(survivors).hasSize(1);
        verify(decisionRepo, never()).insert(any());
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
        verify(decisionRepo, never()).countBySymbolAndReasonCode(any(), any());
    }

    @Test
    void reconcileFinalizesPendingExitWhenBrokerEmpty() {
        ExecutorPosition p = pendingExitPosition(31L, "PSMT", new BigDecimal("193.87"),
                new BigDecimal("190"), "stop-31", "HARD_STOP", "close-31", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        // Broker no longer holds the position (not seeded), and the exit order now reports
        // FILLED (not WORKING/PARTIALLY_FILLED) -> finalization gate is satisfied.
        gateway.seedOrder(new BrokerOrder("close-31", "ref-31", "PSMT", OrderRole.OTHER,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("188.50"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        ArgumentCaptor<BigDecimal> exitPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(positionRepo).close(eq(31L), exitPriceCaptor.capture(), realizedRCaptor.capture(),
                eq("HARD_STOP"), sourceCaptor.capture());
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("188.50");
        assertThat(sourceCaptor.getValue()).isEqualTo("FILL");
        assertThat(realizedRCaptor.getValue()).isNotNull();

        ArgumentCaptor<Instant> expiryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cooldownRepo).add(eq("PSMT"), eq("HARD_STOP"), expiryCaptor.capture(), any());
        assertThat(expiryCaptor.getValue()).isEqualTo(NOW.plus(java.time.Duration.ofDays(10)));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("LOG_HARD_EXIT");
        assertThat(log.reasonCode()).isEqualTo("HARD_STOP");
        assertThat(log.symbol()).isEqualTo("PSMT");

        assertThat(survivors).isEmpty();
    }

    @Test
    void reconcileFinalizesPendingExitUsingStampedFillPriceWhenNoLegMatched() {
        // No broker order carries the exit_order_id at all (e.g. the fake/adapter dropped it
        // once fully filled) -> falls back to the fill price stamped by markPendingExit at
        // submit time, still tagged source FILL (not a MARK guess).
        ExecutorPosition p = pendingExitPosition(32L, "SOFT1", new BigDecimal("100"),
                new BigDecimal("95"), "stop-32", "SOFT_CHANDELIER", "close-32",
                new BigDecimal("102.5"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).close(eq(32L), eq(new BigDecimal("102.5")), any(),
                eq("SOFT_CHANDELIER"), eq("FILL"));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("RECONCILE_CLOSE");

        assertThat(survivors).isEmpty();
    }

    @Test
    void reconcileFinalizesPendingExitFallsBackToActiveStopWhenNoFillDataAtAll() {
        // Neither a matched filled leg nor a stamped pending_exit_fill_price -> last resort is
        // active_stop, tagged source MARK (explicitly NOT a fill price).
        ExecutorPosition p = pendingExitPosition(33L, "NOPRICE", new BigDecimal("100"),
                new BigDecimal("95"), "stop-33", "HARD_STOP", "close-33", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).close(eq(33L), eq(new BigDecimal("95")), any(),
                eq("HARD_STOP"), eq("MARK"));

        assertThat(survivors).isEmpty();
    }

    @Test
    void takeProfitLegFilled_closesTakeProfit() {
        ExecutorPosition p = openPosition(2L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-2", "stop-2", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("tp-2", "ref-2", "ACME", OrderRole.TAKE_PROFIT,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("112"), "brk-2"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(2L), eq(new BigDecimal("112")), realizedRCaptor.capture(),
                eq("TAKE_PROFIT"));
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("2.4");

        verify(cooldownRepo).add(eq("ACME"), eq("TAKE_PROFIT"), any(), any());
        assertThat(survivors).isEmpty();
    }

    @Test
    void stillOpen_updatesHighestAndMfe() {
        ExecutorPosition p = openPosition(3L, "BBB", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-3", "stop-3", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("BBB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("108"), null));

        ReconcileService.ReconcileResult result = service.reconcile("c", "run1");
        List<ExecutorPosition> survivors = result.survivors();

        ArgumentCaptor<BigDecimal> highestCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> mfeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(eq(3L), highestCaptor.capture(), mfeCaptor.capture(),
                eq(0), eq(new BigDecimal("95")), eq(null));
        assertThat(highestCaptor.getValue()).isEqualByComparingTo("108");
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("1.6");

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());

        // A position the broker actually holds is filled — never flagged unfilled.
        assertThat(result.unfilledIds()).isEmpty();
        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.symbol()).isEqualTo("BBB");
        assertThat(survivor.highestPrice()).isEqualByComparingTo("108");
        assertThat(survivor.mfeR()).isEqualByComparingTo("1.6");
    }

    @Test
    void maintenanceSyncsEntryPriceFromBrokerBasis() {
        // Verified prod bug (PSMT): booked entry_price 193.88 (the submitted limit) never
        // corrected to the broker's real fill 193.87 -> slippage always computed as 0.
        ExecutorPosition p = new ExecutorPosition(20L, "c", "PSMT", "BUY", BigDecimal.TEN,
                new BigDecimal("193.88"), new BigDecimal("190"), new BigDecimal("190"), 1, null,
                List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-20", null,
                BigDecimal.ZERO, 0, null, null, null, null, "stop-20",
                null, null, null, null, 0, null, null,
                new BigDecimal("193.88"), null, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("PSMT", "BUY", BigDecimal.TEN,
                new BigDecimal("193.87"), new BigDecimal("195"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).syncEntryPrice(20L, new BigDecimal("193.87"));

        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.entryPrice()).isEqualByComparingTo("193.87");
        assertThat(survivor.submittedLimitPrice()).isEqualByComparingTo("193.88");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("SYNC");
        assertThat(log.reasonCode()).isEqualTo("ENTRY_PRICE_SYNC");
        assertThat(log.symbol()).isEqualTo("PSMT");
        assertThat(log.inputsSnapshot().get("old_entry_price").decimalValue())
                .isEqualByComparingTo("193.88");
        assertThat(log.inputsSnapshot().get("new_entry_price").decimalValue())
                .isEqualByComparingTo("193.87");
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(20L);
    }

    @Test
    void maintenanceDoesNotLogSyncWhenBasisUnchanged() {
        ExecutorPosition p = openPosition(21L, "STAB", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-21", "stop-21", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("STAB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("104"), null));

        service.reconcile("c", "run1");

        verify(positionRepo, never()).syncEntryPrice(anyLong(), any());
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void stillOpen_pinsSectorEntryDayHighAndTranche2FieldsThroughReconcile() {
        // Task-1 review carry-over: ReconcileService's still-open position-copy must not drop
        // sector/entryDayHigh/tranche2OrderId/tranche2StopOrderId — pin the pass-through here.
        ExecutorPosition p = new ExecutorPosition(7L, "c", "BBB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null,
                List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-7", null,
                BigDecimal.ZERO, 0, null, null, null, null, "stop-7",
                "Technology", new BigDecimal("101.5"), "ord-2", "stop-2", 0, null, null,
                null, null, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("BBB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("108"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.sector()).isEqualTo("Technology");
        assertThat(survivor.entryDayHigh()).isEqualByComparingTo("101.5");
        assertThat(survivor.tranche2OrderId()).isEqualTo("ord-2");
        assertThat(survivor.tranche2StopOrderId()).isEqualTo("stop-2");
    }

    @Test
    void stillOpenShort_favorableExtremeIsMinimum() {
        ExecutorPosition p = openPosition(5L, "SHORT1", "SELL", new BigDecimal("100"),
                new BigDecimal("105"), "brk-5", "stop-5", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SHORT1", "SELL", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("94"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        ArgumentCaptor<BigDecimal> highestCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> mfeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(eq(5L), highestCaptor.capture(), mfeCaptor.capture(),
                eq(0), eq(new BigDecimal("105")), eq(null));
        assertThat(highestCaptor.getValue()).isEqualByComparingTo("94");
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("1.2");

        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.highestPrice()).isEqualByComparingTo("94");
        assertThat(survivor.mfeR()).isEqualByComparingTo("1.2");
    }

    @Test
    void stillOpenShort_adverseMoveKeepsPriorFavorableExtreme() {
        ExecutorPosition p = openPosition(6L, "SHORT2", "SELL", new BigDecimal("100"),
                new BigDecimal("105"), "brk-6", "stop-6", new BigDecimal("98"), new BigDecimal("0.4"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SHORT2", "SELL", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("103"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        ArgumentCaptor<BigDecimal> highestCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> mfeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(eq(6L), highestCaptor.capture(), mfeCaptor.capture(),
                eq(0), eq(new BigDecimal("105")), eq(null));
        // favorable extreme (the low) must not move against the position when price rises
        assertThat(highestCaptor.getValue()).isEqualByComparingTo("98");
        // mfeR keeps the best-ever R, not the current (worse) R
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("0.4");

        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).highestPrice()).isEqualByComparingTo("98");
    }

    @Test
    void tranche2Position_t1ExitFilled_staysOpenAndEscalates() {
        // t2 position (has tranche2OrderId/tranche2StopOrderId); the t1 TAKE_PROFIT leg fills.
        // v1 cannot safely TRIM the row to the surviving tranche, so it must neither close nor
        // silently keep it — it escalates and leaves the row OPEN.
        ExecutorPosition p = new ExecutorPosition(8L, "c", "ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null,
                List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-8", null,
                BigDecimal.ZERO, 0, null, null, null, null, "stop-8",
                null, null, "ord-t2", "stop-t2", 0, null, null, null, null, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("tp-8", "ref-8", "ACME", OrderRole.TAKE_PROFIT,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("112"), "brk-8"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("TRANCHE2_DESYNC");
        assertThat(log.symbol()).isEqualTo("ACME");

        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).status()).isEqualTo("OPEN");
        assertThat(survivors.get(0).id()).isEqualTo(8L);
    }

    @Test
    void tranche2Position_t2StopLegRecognizedAsOwnLeg_staysOpenAndEscalates() {
        // The filled leg matches ONLY via tranche2StopOrderId — matchesPosition must recognize it
        // as belonging to this position (not "foreign"/unmatched), and because this is a t2
        // position it must escalate rather than fall through to a silent updateMaintenance.
        ExecutorPosition p = new ExecutorPosition(9L, "c", "ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null,
                List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-9", null,
                BigDecimal.ZERO, 0, null, null, null, null, "stop-9",
                null, null, "ord-t2-9", "stop-t2-9", 0, null, null, null, null, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        // Only the tranche-2 stop leg id matches (not brokerOrderId/stopOrderId/tranche2OrderId).
        gateway.seedOrder(new BrokerOrder("stop-t2-9", "ref-9", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("90"), "unrelated-parent"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE2_DESYNC");

        assertThat(survivors).hasSize(1);
    }

    @Test
    void unfilledGtdEntry_stillWorkingAtBroker_survivesInsteadOfReconcileGone() {
        // A just-placed GTD limit entry has no broker position yet (bp == null) and no filled
        // exit leg — but its ENTRY order is still WORKING. That must NOT be closed as
        // RECONCILE_GONE: the EntryExpiryService owns that lifecycle (cancel after gtd days).
        ExecutorPosition p = openPosition(11L, "NEWPOS", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-11", "stop-11", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("brk-11", "sig-1", "NEWPOS", OrderRole.OTHER,
                OrderStatus.WORKING, BigDecimal.TEN, BigDecimal.ZERO, null, null));

        ReconcileService.ReconcileResult result = service.reconcile("c", "run1");
        List<ExecutorPosition> survivors = result.survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(decisionRepo, never()).insert(any());
        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).id()).isEqualTo(11L);
        assertThat(survivors.get(0).status()).isEqualTo("OPEN");
        // ... and it must be flagged unfilled, so the pipeline keeps hard triggers off it.
        assertThat(result.unfilledIds()).containsExactly(11L);
    }

    @Test
    void unfilledGtdEntry_partiallyFilledAtBroker_survives() {
        ExecutorPosition p = openPosition(12L, "PARTPOS", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-12", "stop-12", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("brk-12", "sig-1", "PARTPOS", OrderRole.OTHER,
                OrderStatus.PARTIALLY_FILLED, BigDecimal.TEN, new BigDecimal("4"),
                new BigDecimal("100"), null));

        ReconcileService.ReconcileResult result = service.reconcile("c", "run1");
        List<ExecutorPosition> survivors = result.survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).id()).isEqualTo(12L);
        assertThat(result.unfilledIds()).containsExactly(12L);
    }

    @Test
    void confirmedFill_clearsEntryExpiryMarker() {
        // entry_expires_at doubles as the persisted "unfilled" flag for LLM-exit gating: once
        // the broker actually holds the position (confirmed fill), reconcile must clear it —
        // otherwise exit_position would keep rejecting a genuinely filled position NOT_FILLED.
        ExecutorPosition p = new ExecutorPosition(13L, "c", "FILLPOS", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null,
                List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-13",
                new BigDecimal("100"), BigDecimal.ZERO, 0, null, null, null, null, "stop-13",
                null, null, null, null, 0, null, "2026-07-10T00:00:00Z",
                null, null, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("FILLPOS", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("104"), null));

        ReconcileService.ReconcileResult result = service.reconcile("c", "run1");

        verify(positionRepo).clearEntryExpiry(13L);
        assertThat(result.unfilledIds()).isEmpty();
        assertThat(result.survivors()).hasSize(1);
        assertThat(result.survivors().get(0).entryExpiresAt()).isNull();
    }

    @Test
    void brokerPositionWithoutBookRowIsFlaggedAsOrphan() {
        when(positionRepo.findOpen()).thenReturn(List.of());

        gateway.seedPosition(new BrokerPosition("GHOST", "BUY", BigDecimal.TEN,
                new BigDecimal("50"), new BigDecimal("55"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.reasonCode()).isEqualTo("ORPHAN_POSITION");
        assertThat(log.symbol()).isEqualTo("GHOST");
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");

        verify(telegram).notifyAlert(eq("GHOST"), eq("ORPHAN_POSITION"), eq("CRITICAL"), any());

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).isEmpty();
    }

    @Test
    void brokerPositionMatchingOpenBookRowIsNotFlaggedAsOrphan() {
        ExecutorPosition p = openPosition(10L, "BBB", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-10", "stop-10", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("BBB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("108"), null));

        service.reconcile("c", "run-1");

        verify(decisionRepo, never()).insert(argThatReasonCodeIs("ORPHAN_POSITION"));
        verify(telegram, never()).notifyAlert(any(), eq("ORPHAN_POSITION"), any(), any());
        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    private static DecisionLog argThatReasonCodeIs(String reasonCode) {
        return org.mockito.ArgumentMatchers.argThat(d -> reasonCode.equals(d.reasonCode()));
    }

    @Test
    void brokerUnavailable_escalatesAndReturnsUnchanged() {
        ExecutorPosition p = openPosition(4L, "CCC", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-4", "stop-4", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.unavailable = true;

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.symbol()).isNull();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any());

        assertThat(survivors).isEqualTo(List.of(p));
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
