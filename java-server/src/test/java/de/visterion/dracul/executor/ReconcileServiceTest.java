package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerClosedPosition;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final CooldownRepository cooldownRepo = mock(CooldownRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TelegramNotifier telegram = mock(TelegramNotifier.class);
    private final ExecutorNotifier executorNotifier = mock(ExecutorNotifier.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ReconcileService service;

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

    @org.junit.jupiter.api.AfterEach
    void detachAppender() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReconcileService.class))
                .detachAndStopAllAppenders();
    }

    /** WARN-level messages ReconcileService emitted during the call. Filtered to WARN so a test
     *  asserting "this was reported" cannot be satisfied by an unrelated INFO/DEBUG line. */
    private List<String> logLines() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Every reason code written to the decision log during the call. Tolerates zero rows: a
     *  test asserting that a code was NOT written must not fail merely because nothing was. */
    private List<String> reasonCodes() {
        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues().stream().map(DecisionLog::reasonCode).toList();
    }

    @BeforeEach
    void setUp() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ReconcileService.class);
        appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new ReconcileService(gateway, positionRepo, decisionRepo, cooldownRepo,
                ruleVersions, mapper, telegram, executorNotifier, 10, 24, legRepo, clock);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, String brokerOrderId, String stopOrderId,
            BigDecimal highest, BigDecimal mfeR) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, entry, initialStop,
                initialStop, 1, null, List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN",
                brokerOrderId, highest, mfeR, 0, null, null, null, null, stopOrderId,
                null, null, null, null, 0, null, null, null, null, null, null, false, null, null);
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
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(1L), exitPriceCaptor.capture(), realizedRCaptor.capture(),
                eq("HARD_STOP"), eq("FILL"), rValueCaptor.capture());
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("95");
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("-1.0");
        // r_value is the entry/stop denominator computeR actually divided by (100 - 95).
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("5");

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
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any(), any());
        verify(executorNotifier).notifyExit(any(), any(), any(), any(), any());
    }

    private ExecutorPosition pendingExitPosition(long id, String symbol, BigDecimal entry,
            BigDecimal initialStop, String stopOrderId, String pendingExitReason,
            String exitOrderId, BigDecimal pendingExitFillPrice) {
        return new ExecutorPosition(id, "c", symbol, "BUY", BigDecimal.TEN, entry, initialStop,
                initialStop, 1, null, List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN",
                "brk-" + id, null, null, 0, null, null, null, null, stopOrderId,
                null, null, null, null, 0, null, null, null,
                pendingExitReason, exitOrderId, pendingExitFillPrice, false, null, null);
    }

    @Test
    void reconcileDoesNotCloseWhileBrokerStillHoldsPosition() {
        // Verified prod incident (PSMT 2026-07-13): a hard trigger already flattened and stamped
        // a pending exit, but the broker still reports the position held (7 shares) with a
        // working SELL exit order. Closing here would be the exact incident: wrong exit
        // price/R and a mismatched book vs. broker state. Must survive untouched instead.
        ExecutorPosition p = pendingExitPosition(30L, "SYNP", new BigDecimal("100.00"),
                new BigDecimal("96.13"), "stop-30", "HARD_STOP", "close-30", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SYNP", "BUY", new BigDecimal("7"),
                new BigDecimal("100.00"), new BigDecimal("86.13"), null));
        gateway.seedOrder(new BrokerOrder("close-30", "ref-30", "SYNP", OrderRole.OTHER,
                OrderStatus.WORKING, new BigDecimal("7"), BigDecimal.ZERO, null, null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any(), any());
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
        Instant submittedAt40 = NOW.minus(java.time.Duration.ofHours(25));
        when(positionRepo.exitSubmittedAt(40L)).thenReturn(submittedAt40);
        when(decisionRepo.countBySymbolAndReasonCodeSince("STALE1", "PENDING_EXIT_STALE", submittedAt40))
                .thenReturn(0);

        gateway.seedPosition(new BrokerPosition("STALE1", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).status()).isEqualTo("OPEN");
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());

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
        Instant submittedAt41 = NOW.minus(java.time.Duration.ofHours(48));
        when(positionRepo.exitSubmittedAt(41L)).thenReturn(submittedAt41);
        when(decisionRepo.countBySymbolAndReasonCodeSince("STALE2", "PENDING_EXIT_STALE", submittedAt41))
                .thenReturn(1);

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
        verify(decisionRepo, never()).countBySymbolAndReasonCodeSince(any(), any(), any());
    }

    @Test
    void pendingExitStale_oldEscalationFromPreviousPendingExit_stillEscalatesForCurrentOne() {
        // Reviewer finding (task-4 fix): decision_log rows are never deleted, so a stale
        // escalation for a PREVIOUS pending exit on this symbol (created BEFORE the current
        // exit_submitted_at) must not suppress the escalation for the CURRENT stale pending
        // exit. Suppression is bounded to "since the current exit was submitted".
        ExecutorPosition p = pendingExitPosition(43L, "STALE3", new BigDecimal("100"),
                new BigDecimal("95"), "stop-43", "HARD_STOP", "close-43", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        Instant submittedAt43 = NOW.minus(java.time.Duration.ofHours(25));
        when(positionRepo.exitSubmittedAt(43L)).thenReturn(submittedAt43);
        // An OLD PENDING_EXIT_STALE row exists for this symbol, but it predates the current
        // pending exit's submit time -> the "since" scoped count must not see it.
        when(decisionRepo.countBySymbolAndReasonCodeSince("STALE3", "PENDING_EXIT_STALE", submittedAt43))
                .thenReturn(0);

        gateway.seedPosition(new BrokerPosition("STALE3", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(survivors).hasSize(1);
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("PENDING_EXIT_STALE");
        verify(telegram).notifyAlert(eq("STALE3"), eq("PENDING_EXIT_STALE"), eq("CRITICAL"), any());
    }

    @Test
    void reconcileFinalizesPendingExitWhenBrokerEmpty() {
        ExecutorPosition p = pendingExitPosition(31L, "SYNP", new BigDecimal("100.00"),
                new BigDecimal("96.13"), "stop-31", "HARD_STOP", "close-31", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        // Broker no longer holds the position (not seeded), and the exit order now reports
        // FILLED (not WORKING/PARTIALLY_FILLED) -> finalization gate is satisfied.
        gateway.seedOrder(new BrokerOrder("close-31", "ref-31", "SYNP", OrderRole.OTHER,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("94.63"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        ArgumentCaptor<BigDecimal> exitPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(positionRepo).close(eq(31L), exitPriceCaptor.capture(), realizedRCaptor.capture(),
                eq("HARD_STOP"), sourceCaptor.capture(), any());
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("94.63");
        assertThat(sourceCaptor.getValue()).isEqualTo("FILL");
        assertThat(realizedRCaptor.getValue()).isNotNull();

        ArgumentCaptor<Instant> expiryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cooldownRepo).add(eq("SYNP"), eq("HARD_STOP"), expiryCaptor.capture(), any());
        assertThat(expiryCaptor.getValue()).isEqualTo(NOW.plus(java.time.Duration.ofDays(10)));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("LOG_HARD_EXIT");
        assertThat(log.reasonCode()).isEqualTo("HARD_STOP");
        assertThat(log.symbol()).isEqualTo("SYNP");

        assertThat(survivors).isEmpty();
        verify(executorNotifier).notifyExit(any(), any(), any(), any(), any());
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
                eq("SOFT_CHANDELIER"), eq("FILL"), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("RECONCILE_CLOSE");

        assertThat(survivors).isEmpty();
    }

    @Test
    void reconcileFinalizesPendingExitFallsBackToActiveStopWhenNoFillDataAtAll() {
        // Neither a matched filled leg nor a stamped pending_exit_fill_price -> last resort is
        // active_stop, tagged source MARK_FLATTEN (explicitly NOT a fill price, and distinct from
        // a leg-produced MARK estimate: a flatten cancelled the protective leg before closing).
        ExecutorPosition p = pendingExitPosition(33L, "NOPRICE", new BigDecimal("100"),
                new BigDecimal("95"), "stop-33", "HARD_STOP", "close-33", null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).close(eq(33L), eq(new BigDecimal("95")), any(),
                eq("HARD_STOP"), eq("MARK_FLATTEN"), any());

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
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(2L), eq(new BigDecimal("112")), realizedRCaptor.capture(),
                eq("TAKE_PROFIT"), eq("FILL"), rValueCaptor.capture());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("2.4");
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("5");

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
                eq(0), eq(new BigDecimal("95")), eq(null), any());
        assertThat(highestCaptor.getValue()).isEqualByComparingTo("108");
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("1.6");

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());

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
        // Verified prod bug: booked entry_price 100.01 (the submitted limit) never
        // corrected to the broker's real fill 100.00 -> slippage always computed as 0.
        ExecutorPosition p = new ExecutorPosition(20L, "c", "SYNP", "BUY", BigDecimal.TEN,
                new BigDecimal("100.01"), new BigDecimal("96.13"), new BigDecimal("96.13"), 1, null,
                List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-20", null,
                BigDecimal.ZERO, 0, null, null, null, null, "stop-20",
                null, null, null, null, 0, null, null,
                new BigDecimal("100.01"), null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SYNP", "BUY", BigDecimal.TEN,
                new BigDecimal("100.00"), new BigDecimal("101.13"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).syncEntryPrice(20L, new BigDecimal("100.00"));

        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.entryPrice()).isEqualByComparingTo("100.00");
        assertThat(survivor.submittedLimitPrice()).isEqualByComparingTo("100.01");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("SYNC");
        assertThat(log.reasonCode()).isEqualTo("ENTRY_PRICE_SYNC");
        assertThat(log.symbol()).isEqualTo("SYNP");
        assertThat(log.inputsSnapshot().get("old_entry_price").decimalValue())
                .isEqualByComparingTo("100.01");
        assertThat(log.inputsSnapshot().get("new_entry_price").decimalValue())
                .isEqualByComparingTo("100.00");
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
                null, null, null, null, false, null, null);
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
                eq(0), eq(new BigDecimal("105")), eq(null), any());
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
                eq(0), eq(new BigDecimal("105")), eq(null), any());
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
                null, null, "ord-t2", "stop-t2", 0, null, null, null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("tp-8", "ref-8", "ACME", OrderRole.TAKE_PROFIT,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("112"), "brk-8"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any(), any());

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
                null, null, "ord-t2-9", "stop-t2-9", 0, null, null, null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        // Only the tranche-2 stop leg id matches (not brokerOrderId/stopOrderId/tranche2OrderId).
        gateway.seedOrder(new BrokerOrder("stop-t2-9", "ref-9", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("90"), "unrelated-parent"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any(), any());

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

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
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

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
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
                null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("FILLPOS", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("104"), null));

        ReconcileService.ReconcileResult result = service.reconcile("c", "run1");

        verify(positionRepo).clearEntryExpiry(13L);
        assertThat(result.unfilledIds()).isEmpty();
        assertThat(result.survivors()).hasSize(1);
        assertThat(result.survivors().get(0).entryExpiresAt()).isNull();
        verify(executorNotifier).notifyEntryFilled(any(), any(), any(), any());
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
        // "the book never knew about this holding" -- the weaker of the two ORPHAN_POSITION
        // conditions, and queryable apart from the post-loop one rather than only readable.
        assertThat(log.inputsSnapshot().path("phase").asString()).isEqualTo("PRE_LOOP");

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

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any(), any());

        assertThat(survivors).isEqualTo(List.of(p));
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    // --- A-2b: RECONCILE_GONE books real broker fills, labels the estimate fallback ---------

    @Test
    void reconcileGone_withClosedPositionMatch_booksRealFillsAndSourceFill() {
        // Verified prod incident (SYNG 2026-07-17): a bracket filled at a gapped-down open and
        // stopped out entirely between two reconcile cycles. The book never saw it OPEN, so the
        // reconciler previously booked placeholders (submitted limit as entry, stop as exit,
        // exit_price_source=null). Now a real closed-position match must book the real fill.
        ExecutorPosition p = openPosition(20L, "SYNG", "BUY", new BigDecimal("100.00"),
                new BigDecimal("67.97"), "brk-20", "stop-20", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedClosedPosition(new BrokerClosedPosition("SYNG", new BigDecimal("61.78"),
                new BigDecimal("61.53"), new BigDecimal("-0.25"), "sig-1"));

        service.reconcile("c", "run1");

        verify(positionRepo).syncEntryPrice(20L, new BigDecimal("61.78"));

        // R must be measured against the ORIGINAL planned risk (planned entry vs initial stop),
        // NOT the synced real entry vs stop -- a gapped-down fill lands below its stop, which
        // would flip the entry-stop denominator negative and turn this ~0.25-loss into a
        // positive R (see the SYNG incident this fix addresses).
        BigDecimal expectedR = new BigDecimal("61.53").subtract(new BigDecimal("61.78"))
                .divide(new BigDecimal("100.00").subtract(new BigDecimal("67.97")), 6, RoundingMode.HALF_UP); // ~ -0.007805

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(20L), eq(new BigDecimal("61.53")), realizedRCaptor.capture(),
                eq("RECONCILE_GONE"), eq("FILL"), rValueCaptor.capture());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo(expectedR);
        assertThat(realizedRCaptor.getValue().signum()).isLessThan(0);
        // r_value here must be the PLANNED-risk denominator (100.00 - 67.97), NOT the live
        // entry/stop denominator computeR would have used -- same reason realizedR is measured
        // against planned risk (see realizedRAgainstPlannedRisk()).
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("32.03");
        assertThat(realizedRCaptor.getValue())
                .isEqualByComparingTo(new BigDecimal("61.53").subtract(new BigDecimal("61.78"))
                        .divide(rValueCaptor.getValue(), 6, RoundingMode.HALF_UP));
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
    }

    @Test
    void reconcileGone_matchWithBigAdverseGap_booksNegativeRNotPositive() {
        // Gapped-down fill 61.78 then closes far below at 47.43 -- a real ~0.45R LOSS. The OLD
        // (buggy) entry-stop denominator would report +2.32R (a win). Pin it strictly negative.
        ExecutorPosition p = openPosition(22L, "SYNG", "BUY", new BigDecimal("100.00"),
                new BigDecimal("67.97"), "brk-22", "stop-22", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedClosedPosition(new BrokerClosedPosition("SYNG", new BigDecimal("61.78"),
                new BigDecimal("47.43"), new BigDecimal("-14.35"), "sig-1"));

        service.reconcile("c", "run1");

        BigDecimal expectedR = new BigDecimal("47.43").subtract(new BigDecimal("61.78"))
                .divide(new BigDecimal("100.00").subtract(new BigDecimal("67.97")), 6, RoundingMode.HALF_UP); // ~ -0.448

        ArgumentCaptor<BigDecimal> cap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(22L), eq(new BigDecimal("47.43")), cap.capture(),
                eq("RECONCILE_GONE"), eq("FILL"), any());
        assertThat(cap.getValue()).isEqualByComparingTo(expectedR);
        assertThat(cap.getValue().signum()).isLessThan(0); // sharpest anti-sign-flip assertion
    }

    @Test
    void reconcileGone_noClosedPositionMatch_labelsSourceReconcileGone() {
        ExecutorPosition p = openPosition(21L, "SYNG", "BUY", new BigDecimal("100.00"),
                new BigDecimal("67.97"), "brk-21", "stop-21", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        // No closedPositions seeded -> FakeExecutionGateway returns an empty list.

        service.reconcile("c", "run1");

        verify(positionRepo, never()).syncEntryPrice(anyLong(), any());

        BigDecimal expectedR = new BigDecimal("67.97").subtract(new BigDecimal("100.00"))
                .divide(new BigDecimal("100.00").subtract(new BigDecimal("67.97")), 6, RoundingMode.HALF_UP);

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(21L), eq(new BigDecimal("67.97")), realizedRCaptor.capture(),
                eq("RECONCILE_GONE"), eq("RECONCILE_GONE"), any());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo(expectedR);
    }

    @Test
    void reconcileGone_matchWithInvalidPrices_fallsBackToLabeledEstimate() {
        // Guard against a malformed upstream fill (Saxo field-mapping bug): a matched closed
        // position with a non-positive/null open price must be treated as unusable, not booked
        // as a real fill (entry=0/exit=0 would corrupt realizedR far worse than the estimate).
        ExecutorPosition p = openPosition(22L, "SYNG", "BUY", new BigDecimal("100.00"),
                new BigDecimal("67.97"), "brk-22", "stop-22", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedClosedPosition(new BrokerClosedPosition("SYNG", BigDecimal.ZERO,
                new BigDecimal("61.53"), new BigDecimal("-0.25"), "sig-1"));

        service.reconcile("c", "run1");

        verify(positionRepo, never()).syncEntryPrice(anyLong(), any());

        BigDecimal expectedR = new BigDecimal("67.97").subtract(new BigDecimal("100.00"))
                .divide(new BigDecimal("100.00").subtract(new BigDecimal("67.97")), 6, RoundingMode.HALF_UP);

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(22L), eq(new BigDecimal("67.97")), realizedRCaptor.capture(),
                eq("RECONCILE_GONE"), eq("RECONCILE_GONE"), any());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo(expectedR);
    }

    // -------------------------------------------------------------------
    // r_value persists the ACTUAL denominator realized_r was divided by (bugfix/executor-exit-audit).
    // Symbol and prices below are synthetic (invented for this test, offset from the fix brief's
    // shape so no absolute value matches any real transcript) -- only the ratios that exercise the
    // divergence are preserved.
    // -------------------------------------------------------------------

    @Test
    void reconcileGone_plannedVsLiveShapedMatch_persistsPlannedRiskDenominator() {
        // RECONCILE_GONE matched-fill path: realized_r is measured against the PLANNED risk
        // (submitted-limit entry vs initial stop), not the live entry/stop computeR would use --
        // and r_value must record that SAME planned-risk denominator, not the live one.
        // planned entry 459.54, initial stop 421.00 -> planned risk 38.54.
        // real fill entry 443.76, real exit 416.26 -> pnl -27.50 -> realized_r -0.713544.
        ExecutorPosition p = openPosition(60L, "SYNQ1", "BUY", new BigDecimal("459.54"),
                new BigDecimal("421.00"), "brk-60", "stop-60", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedClosedPosition(new BrokerClosedPosition("SYNQ1", new BigDecimal("443.76"),
                new BigDecimal("416.26"), new BigDecimal("-27.50"), "sig-1"));

        service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(60L), eq(new BigDecimal("416.26")), realizedRCaptor.capture(),
                eq("RECONCILE_GONE"), eq("FILL"), rValueCaptor.capture());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("-0.713544");
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("38.54");
        // the row must be reconcilable against itself: realized_r == pnl / r_value
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo(
                new BigDecimal("416.26").subtract(new BigDecimal("443.76"))
                        .divide(rValueCaptor.getValue(), 6, RoundingMode.HALF_UP));
    }

    @Test
    void reconcileGone_sellShapedMatch_persistsPlannedRiskDenominator() {
        // SELL side of the RECONCILE_GONE planned-risk path: plannedRisk = stop - entry (stop
        // above entry for a short), pnl = realEntry - realExit.
        ExecutorPosition p = openPosition(61L, "SHRT1", "SELL", new BigDecimal("200"),
                new BigDecimal("210"), "brk-61", "stop-61", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedClosedPosition(new BrokerClosedPosition("SHRT1", new BigDecimal("195"),
                new BigDecimal("180"), new BigDecimal("15"), "sig-1"));

        service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(61L), eq(new BigDecimal("180")), realizedRCaptor.capture(),
                eq("RECONCILE_GONE"), eq("FILL"), rValueCaptor.capture());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("1.5");
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("10");
    }

    @Test
    void reconcileGone_matchWithNonPositivePlannedRisk_fallsBackToComputeRInsteadOfBookingNull() {
        // Review finding (code review on 595aa34a, Critical 1): realizedRAgainstPlannedRisk wraps
        // its null-guard result in a non-null RCalc(null, null) now that it returns a record. The
        // fallback at the closePosition call site must still treat THAT as "no override" and fall
        // through to computeR -- exactly like base fell through when the method returned a bare
        // null -- or a matched RECONCILE_GONE close silently books realized_r=NULL instead of a
        // number wherever plannedRisk <= 0.
        //
        // plannedRisk <= 0 here comes from the book's entry_price already sitting BELOW the
        // initial stop (365 < 370) -- exactly what a prior run's syncEntryPrice can leave behind
        // on a gapped-down fill (the SYNG scenario this whole branch exists for), not from
        // entry_price == initial_stop.
        ExecutorPosition p = openPosition(65L, "GAPFAIL", "BUY", new BigDecimal("365"),
                new BigDecimal("370"), "brk-65", "stop-65", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedClosedPosition(new BrokerClosedPosition("GAPFAIL", new BigDecimal("360"),
                new BigDecimal("350"), new BigDecimal("-10"), "sig-1"));

        service.reconcile("c", "run1");

        // computeR(effective, exitPrice): effective.entryPrice() = 360 (synced this run),
        // initialStop = 370 -> denominator -10; exitPrice 350 -> numerator -10 -> R = 1.000000.
        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(65L), eq(new BigDecimal("350")), realizedRCaptor.capture(),
                eq("RECONCILE_GONE"), eq("FILL"), rValueCaptor.capture());
        assertThat(realizedRCaptor.getValue()).isNotNull();
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("1.000000");
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("-10");
    }

    @Test
    void hardStop_syngShapedFill_persistsLiveEntryStopDenominator() {
        // Normal computeR path (not RECONCILE_GONE): r_value must be the live entry/stop
        // denominator, matching whatever realized_r was actually divided by.
        // entry 100.00, stop 67.97 -> denominator 32.03; stopped out exactly at the stop
        // -> realized_r -1.000000.
        ExecutorPosition p = openPosition(62L, "SYNGX", "BUY", new BigDecimal("100.00"),
                new BigDecimal("67.97"), "brk-62", "stop-62", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("stop-62", "ref-62", "SYNGX", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("67.97"), "brk-62"));

        service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(62L), eq(new BigDecimal("67.97")), realizedRCaptor.capture(),
                eq("HARD_STOP"), eq("FILL"), rValueCaptor.capture());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("-1.000000");
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("32.03");
    }

    @Test
    void hardStop_sellShapedFill_persistsLiveEntryStopDenominator() {
        // SELL side of the normal computeR path: denominator = stop - entry.
        ExecutorPosition p = openPosition(63L, "SHRT2", "SELL", new BigDecimal("200"),
                new BigDecimal("210"), "brk-63", "stop-63", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("stop-63", "ref-63", "SHRT2", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("190"), "brk-63"));

        service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(63L), eq(new BigDecimal("190")), realizedRCaptor.capture(),
                eq("HARD_STOP"), eq("FILL"), rValueCaptor.capture());
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("1.000000");
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("10");
    }

    @Test
    void hardStop_zeroDenominator_persistsNullRValueNotAMeaninglessNumber() {
        // Degenerate case (entry == initial stop -> zero-risk denominator): computeR already
        // returns null for realized_r in this case. r_value must ALSO stay null instead of
        // persisting a meaningless "0" or the numerator -- there is nothing valid to reconcile
        // realized_r against when realized_r itself is null.
        ExecutorPosition p = openPosition(64L, "ZERORISK", "BUY", new BigDecimal("100"),
                new BigDecimal("100"), "brk-64", "stop-64", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("stop-64", "ref-64", "ZERORISK", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("100"), "brk-64"));

        service.reconcile("c", "run1");

        verify(positionRepo).close(eq(64L), eq(new BigDecimal("100")), isNull(),
                eq("HARD_STOP"), eq("FILL"), isNull());
    }

    // -------------------------------------------------------------------
    // filled-stop detection reads the history view, not the open-orders view (BUG-S12)
    // -------------------------------------------------------------------

    @Test
    void filledStop_isDetectedEvenThoughItIsNotAnOpenOrder() {
        // BUG-S12: findFilledExitLeg only ever saw gateway.orders(), which is an OPEN-orders view
        // on every broker (Saxo /port/v1/orders/me, Alpaca's status=open default) — a filled order
        // is by definition absent from it, so the HARD_STOP path was unreachable and a stop-out
        // could only ever be booked as RECONCILE_GONE. The fill must come from the history call.
        ExecutorPosition p = openPosition(50L, "SYNA", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "2000000001", "2000000002", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        // Seeded FILLED -> the fake surfaces it only via filledOrdersSince, exactly like Agora.
        gateway.seedOrder(new BrokerOrder("2000000002", "ref-50", "SYNA", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("95"), "2000000001"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(gateway.orders("c")).isEmpty();   // the fill is genuinely not an open order
        assertThat(gateway.filledOrdersSinceArgs).containsExactly(NOW.minus(Duration.ofHours(72)));

        verify(positionRepo).close(eq(50L), eq(new BigDecimal("95")), any(),
                eq("HARD_STOP"), eq("FILL"), any());
        verify(cooldownRepo).add(eq("SYNA"), eq("HARD_STOP"), any(), any());
        assertThat(survivors).isEmpty();
    }

    @Test
    void filledStopReportedWithoutARole_isStillBookedAsHardStop() {
        // The history endpoint carries real fills but no bracket-leg structure, so Agora falls
        // back to a best-effort role hint and a stop leg can come back as OTHER. Our own
        // stop_order_id is the better evidence of what that order is; dropping it on the role
        // filter would miss the fill and demote the close to a RECONCILE_GONE estimate.
        ExecutorPosition p = openPosition(51L, "SYNB", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "2000000003", "2000000004", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("2000000004", null, "SYNB", OrderRole.OTHER,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("94.50"), null));

        service.reconcile("c", "run1");

        verify(positionRepo).close(eq(51L), eq(new BigDecimal("94.50")), any(),
                eq("HARD_STOP"), eq("FILL"), any());
    }

    @Test
    void filledOrderHistoryUnavailable_leavesVanishedPositionOpenAndEscalates() {
        // Missing evidence is not evidence of absence: without the fill history a vanished
        // position cannot be told apart from one that closed at a price we never saw, so booking
        // the RECONCILE_GONE estimate here would invent a realized R. The row must stay OPEN and
        // an operator has to look — this used to fail-soft into exactly the close this test now
        // forbids.
        ExecutorPosition p = openPosition(52L, "SYNA", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "2000000005", "2000000006", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.filledOrdersUnavailable = true;
        gateway.seedClosedPosition(new BrokerClosedPosition("SYNA", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("-50"), "sig-1"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog esc = captor.getValue();
        assertThat(esc.action()).isEqualTo("ESCALATE");
        assertThat(esc.reasonCode()).isEqualTo("FILL_HISTORY_UNAVAILABLE");
        assertThat(esc.inputsSnapshot().path("withheld").asText()).isEqualTo("RECONCILE_GONE");
        assertThat(esc.inputsSnapshot().path("book_qty").asInt()).isEqualTo(10);
        assertThat(esc.inputsSnapshot().has("legs_qty")).isFalse();
        assertThat(esc.inputsSnapshot().has("broker_qty")).isFalse();
        assertThat(survivors).extracting(ExecutorPosition::id).containsExactly(52L);
    }

    @Test
    void filledOrderHistoryUnavailable_doesNotSyncQtyDownOnTheLegacyChain() {
        // Same evidence gap, reached from the legacy (legless) chain's shrink case: a smaller
        // broker holding used to fall through the final `else` into updateMaintenance, which
        // syncs qty down unconditionally and books a bare QTY_SYNC row -- no TRIM, no realized R
        // for the missing shares. With the fill history broken that shortfall cannot be told
        // apart from an unobserved stop fill, so it must escalate instead, exactly like the
        // leg-path shortfall gate.
        ExecutorPosition p = openPosition(53L, "SYNA", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "2000000007", "2000000008", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("SYNA", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.filledOrdersThrows = new RuntimeException("history endpoint down");

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo, never()).syncQty(anyLong(), any());
        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog esc = captor.getValue();
        assertThat(esc.action()).isEqualTo("ESCALATE");
        assertThat(esc.reasonCode()).isEqualTo("FILL_HISTORY_UNAVAILABLE");
        assertThat(esc.inputsSnapshot().path("withheld").asText()).isEqualTo("QTY_SYNC_SHORTFALL");
        assertThat(esc.inputsSnapshot().path("book_qty").asInt()).isEqualTo(10);
        assertThat(esc.inputsSnapshot().has("legs_qty")).isFalse();
        assertThat(esc.inputsSnapshot().path("broker_qty").asInt()).isEqualTo(6);
        assertThat(survivors).hasSize(1);
        assertThat(survivors.getFirst().qty()).isEqualByComparingTo("10");
    }

    // -------------------------------------------------------------------
    // qty sync — `qty` means shares HELD (BUG-S9)
    // -------------------------------------------------------------------

    /** Two-tranche position whose tranche-2 limit is still working: the book was grown to the
     *  intended total at placement, the broker holds only tranche 1. */
    private ExecutorPosition unfilledTranche2Position(long id, String symbol, BigDecimal bookedQty) {
        return new ExecutorPosition(id, "c", symbol, "BUY", bookedQty, new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), 2, null, List.of(), "sig-1", "agent",
                "2026-07-01", null, "OPEN", "2000000001", null, null, 0, null, null, null, null,
                "2000000002", null, null, "2000000003", "2000000004", 0, null, null, null,
                null, null, null, false, null, null);
    }

    @Test
    void unfilledTranche2_syncsBookQtyDownToWhatTheBrokerHolds() {
        // Prod 2026-08-06: SYNA booked at 12 shares (tranche 1 = 6, plus an intended tranche-2 6
        // whose limit was still Working) while the broker held 6. Every quantity-based action —
        // the exit_position flatten remainder, the exposure/heat veto inputs — then computed on a
        // phantom 6 shares. The book must follow the broker.
        ExecutorPosition p = unfilledTranche2Position(40L, "SYNA", new BigDecimal("12"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SYNA", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).syncQty(40L, new BigDecimal("6"));

        // The survivor handed on to the maintenance pipeline (hard triggers, LLM context,
        // exposure/heat) must already carry the held quantity, not the stale booked one.
        assertThat(survivors).hasSize(1);
        assertThat(survivors.getFirst().qty()).isEqualByComparingTo("6");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeastOnce()).insert(logCaptor.capture());
        DecisionLog sync = logCaptor.getAllValues().stream()
                .filter(l -> "QTY_SYNC".equals(l.reasonCode()))
                .findFirst().orElseThrow();
        assertThat(sync.action()).isEqualTo("SYNC");
        assertThat(sync.symbol()).isEqualTo("SYNA");
        assertThat(sync.inputsSnapshot().path("old_qty").asInt()).isEqualTo(12);
        assertThat(sync.inputsSnapshot().path("new_qty").asInt()).isEqualTo(6);
    }

    @Test
    void qtyAlreadyMatchingBroker_doesNotSyncOrLog() {
        // Idempotence: the sync must be a no-op once the tranche-2 fill has landed, or every
        // reconcile pass would write a redundant row.
        ExecutorPosition p = unfilledTranche2Position(41L, "SYNB", new BigDecimal("12"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SYNB", "BUY", new BigDecimal("12"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        service.reconcile("c", "run1");

        verify(positionRepo, never()).syncQty(anyLong(), any());
    }

    @Test
    void brokerQtyMissing_leavesBookQtyAlone() {
        // Fail-soft: a broker payload without a usable quantity must never blank a good book value.
        ExecutorPosition p = unfilledTranche2Position(42L, "SYNA", new BigDecimal("12"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SYNA", "BUY", null,
                new BigDecimal("100"), new BigDecimal("104"), null));

        service.reconcile("c", "run1");

        verify(positionRepo, never()).syncQty(anyLong(), any());
    }

    // ---------------------------------------------------------------------------------------
    // Leg-based reconciliation (BUG-S11). A position whose broker tranches are modelled as
    // executor_position_leg rows reconciles leg by leg instead of escalating TRANCHE2_DESYNC.
    // ---------------------------------------------------------------------------------------

    private ExecutorPosition twoTranchePosition(long id, String symbol, BigDecimal qty,
            BigDecimal entry, BigDecimal initialStop) {
        return new ExecutorPosition(id, "c", symbol, "BUY", qty, entry, initialStop,
                initialStop, 2, null, List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN",
                "ord-1", null, BigDecimal.ZERO, 0, null, null, null, null, "stop-1",
                null, null, "ord-2", "stop-2", 0, null, null, null, null, null, null, false, null, null);
    }

    // ---------------------------------------------------------------------------------------
    // Leg SEEDING. Nothing else in production writes a leg row, so these tests own the rule that
    // makes the whole leg table trustworthy: a leg carries shares HELD at the broker, never
    // shares ORDERED. The quantity comes from the tranche's own WORKING protective stop, which
    // is an IfDone child and therefore only works once its entry has filled.
    // ---------------------------------------------------------------------------------------

    /** A protective stop as the OPEN-orders view reports it while it is live on a filled tranche. */
    private BrokerOrder workingStop(String orderId, String symbol, BigDecimal qty) {
        return new BrokerOrder(orderId, "ref-" + orderId, symbol, OrderRole.STOP_LOSS,
                OrderStatus.WORKING, qty, BigDecimal.ZERO, null, null);
    }

    private ArgumentCaptor<ExecutorPositionLeg> captureSeededLegs() {
        ArgumentCaptor<ExecutorPositionLeg> captor = ArgumentCaptor.forClass(ExecutorPositionLeg.class);
        verify(legRepo, atLeastOnce()).insertIfAbsent(captor.capture());
        return captor;
    }

    @Test
    void filledTrancheOne_seedsALegCarryingTheWorkingStopQuantity() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "ord-1", "stop-1", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("10")));

        service.reconcile("c", "run1");

        ExecutorPositionLeg seeded = captureSeededLegs().getValue();
        assertThat(seeded.positionId()).isEqualTo(1L);
        assertThat(seeded.tranche()).isEqualTo(1);
        assertThat(seeded.entryOrderId()).isEqualTo("ord-1");
        assertThat(seeded.stopOrderId()).isEqualTo("stop-1");
        assertThat(seeded.qty()).isEqualByComparingTo("10");
        assertThat(seeded.status()).isEqualTo(ExecutorPositionLeg.OPEN);
    }

    @Test
    void workingButUnfilledTrancheTwo_seedsNoSecondLeg() {
        // THE case the hard constraint exists for. The tranche-2 entry is a limit order that is
        // still working: the broker has none of its shares. Seeding a leg from the ORDER would
        // make the legs claim 6 + 15 against 6 shares actually held, no fill would explain the
        // difference, and the position would sit in a permanent LEG_QTY_DESYNC escalation while
        // occupying a slot under the MAX_POSITIONS veto.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("6")));
        // The tranche-2 ENTRY is working with 15 ordered shares. Its stop is an IfDone child, so
        // pre-fill it is not a top-level order at all and cannot appear in this view.
        gateway.seedOrder(new BrokerOrder("ord-2", "ref-ord-2", "ACME", OrderRole.ENTRY,
                OrderStatus.WORKING, new BigDecimal("15"), BigDecimal.ZERO, null, null));

        service.reconcile("c", "run1");

        List<ExecutorPositionLeg> seeded = captureSeededLegs().getAllValues();
        assertThat(seeded).hasSize(1);
        assertThat(seeded.getFirst().tranche()).isEqualTo(1);
        assertThat(seeded.getFirst().qty()).isEqualByComparingTo("6");
        assertThat(seeded).noneMatch(l -> l.tranche() == 2);
    }

    @Test
    void bothTranchesFilled_seedsEachLegWithItsOwnQuantityNotTheSum() {
        // The shape that broke the original backfill: the entry ORDERS asked for one split, the
        // broker's protective stops covered another, and the totals agreed so no sum-based check
        // could see it. Each leg follows its own working stop.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("6")));
        gateway.seedOrder(workingStop("stop-2", "ACME", new BigDecimal("14")));

        service.reconcile("c", "run1");

        List<ExecutorPositionLeg> seeded = captureSeededLegs().getAllValues();
        assertThat(seeded).hasSize(2);
        assertThat(seeded).anyMatch(l -> l.tranche() == 1 && l.qty().compareTo(new BigDecimal("6")) == 0
                && "stop-1".equals(l.stopOrderId()));
        assertThat(seeded).anyMatch(l -> l.tranche() == 2 && l.qty().compareTo(new BigDecimal("14")) == 0
                && "stop-2".equals(l.stopOrderId()));
        // The sum is never written onto a single leg.
        assertThat(seeded).noneMatch(l -> l.qty().compareTo(new BigDecimal("20")) == 0);
    }

    @Test
    void brokerDoesNotHoldThePosition_seedsNothing() {
        // No holding on the instrument means no leg can be carrying shares, whatever an order
        // list says.
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "ord-1", "stop-1", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("10")));

        service.reconcile("c", "run1");

        verify(legRepo, never()).insertIfAbsent(any());
    }

    @Test
    void trancheAlreadyCarryingALeg_isNotSeededAgain() {
        // Re-entrancy: reconcile sees the same working stops on every pass. A tranche that already
        // has a row -- in ANY status -- is never re-seeded, so a CLOSED leg cannot be resurrected
        // and the UNIQUE (position_id, tranche) constraint is never tested by a duplicate.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findByPosition(1L)).thenReturn(List.of(
                new ExecutorPositionLeg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("6"),
                        ExecutorPositionLeg.CLOSED, new BigDecimal("95"), "HARD_STOP", null)));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("14"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("6")));
        gateway.seedOrder(workingStop("stop-2", "ACME", new BigDecimal("14")));

        service.reconcile("c", "run1");

        List<ExecutorPositionLeg> seeded = captureSeededLegs().getAllValues();
        assertThat(seeded).hasSize(1);
        assertThat(seeded.getFirst().tranche()).isEqualTo(2);
    }

    @Test
    void stopReportedPartiallyFilled_seedsNoLeg() {
        // A PARTIALLY_FILLED stop's qty is its ORIGINAL total, not the shares still held -- part
        // of the tranche has already exited. Reading it as a holding would book a knowably wrong
        // quantity, so only a WORKING stop is evidence.
        //
        // The broker is made to report the FULL 10 here, deliberately. With a smaller holding the
        // held-qty ceiling would refuse the seed on its own and this test would pass without the
        // WORKING filter existing at all -- which is what it did until this was noticed. At 10
        // against 10 the ceiling cannot fire, so the status filter is the only thing left that
        // can make it pass. (A position feed that has not yet caught up with a partial stop fill
        // reports exactly this.)
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "ord-1", "stop-1", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(new BrokerOrder("stop-1", "ref-stop-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.PARTIALLY_FILLED, new BigDecimal("10"), new BigDecimal("6"), null, null));

        service.reconcile("c", "run1");

        verify(legRepo, never()).insertIfAbsent(any());
        // ... and not because seeding was refused for a different reason.
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("LEG_SEED_EXCEEDS_HOLDING"));
    }

    @Test
    void seededLegsThatWouldClaimMoreThanTheBrokerHolds_areRefusedAndEscalated() {
        // The held-qty ceiling. The production check that established "a WORKING stop's qty is
        // shares HELD" only ever measured tranches that filled COMPLETELY. If a stop child is
        // ever activated at its ORDERED size while its parent only partly filled, the stop
        // over-reports -- and syncLegQuantities would converge to the same wrong number rather
        // than catch it. The broker's own reported holding is the one figure that cannot be
        // inflated that way, so it caps the sum and seeding is refused rather than guessed.
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "ord-1", "stop-1", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("4"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        // The stop claims the full ordered size; only 4 shares were actually bought.
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("10")));

        service.reconcile("c", "run1");

        verify(legRepo, never()).insertIfAbsent(any());
        assertThat(reasonCodes()).contains("LEG_SEED_EXCEEDS_HOLDING");
    }

    @Test
    void anExistingOpenLegCountsAgainstTheHeldQtyCeiling() {
        // Seeding tranche 2 must not push the total past the holding just because tranche 1 was
        // booked on an earlier pass.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("8"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        // 8 already booked + 5 candidate = 13 against 10 held.
        gateway.seedOrder(workingStop("stop-2", "ACME", new BigDecimal("5")));

        service.reconcile("c", "run1");

        verify(legRepo, never()).insertIfAbsent(any());
        assertThat(reasonCodes()).contains("LEG_SEED_EXCEEDS_HOLDING");
    }

    @Test
    void seedingExactlyUpToTheBrokersHolding_isAllowed() {
        // The ceiling is a ceiling, not a strict inequality: legs summing exactly to the holding
        // is the normal, healthy two-tranche shape.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("6")));
        gateway.seedOrder(workingStop("stop-2", "ACME", new BigDecimal("14")));
        when(legRepo.insertIfAbsent(any())).thenReturn(true);

        service.reconcile("c", "run1");

        assertThat(captureSeededLegs().getAllValues()).hasSize(2);
        assertThat(reasonCodes()).doesNotContain("LEG_SEED_EXCEEDS_HOLDING");
        // The audit row is written for each leg actually inserted.
        assertThat(reasonCodes()).filteredOn("LEG_SEEDED"::equals).hasSize(2);
    }

    @Test
    void aWorkingStopOnAnotherInstrument_isNeverReadAsThisPositionsHolding() {
        // Matching on the order id alone would let a stale or mis-booked id import a quantity
        // from an unrelated instrument.
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "ord-1", "stop-1", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "OTHERCO", new BigDecimal("10")));

        service.reconcile("c", "run1");

        verify(legRepo, never()).insertIfAbsent(any());
    }

    @Test
    void aHeldPositionThatCannotBeSeededAtAll_isReportedNotSilentlySkipped() {
        // Degrading to the legacy path is acceptable; being silent about it is not -- an
        // unreported version of this exact condition is why the original defect survived a
        // quarter year. The book records a stop id, the broker holds the shares, and yet no
        // WORKING stop matches it: the protective stop may be gone.
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "ord-1", "stop-1", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        service.reconcile("c", "run1");

        verify(legRepo, never()).insertIfAbsent(any());
        assertThat(logLines()).anyMatch(l -> l.contains("cannot seed legs for ACME")
                && l.contains("protective stop may be gone"));
    }

    @Test
    void anUnfilledEntryIsNotReportedAsUnseedable() {
        // The broker holds nothing: the ordinary pending-entry state. Warning here would fire on
        // every unfilled entry on every pass, which is how a real signal gets buried.
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "ord-1", "stop-1", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedOrder(new BrokerOrder("ord-1", "ref-ord-1", "ACME", OrderRole.ENTRY,
                OrderStatus.WORKING, BigDecimal.TEN, BigDecimal.ZERO, null, null));

        service.reconcile("c", "run1");

        assertThat(logLines()).noneMatch(l -> l.contains("cannot seed legs"));
    }

    @Test
    void aPositionWithTrancheOneBookedAndTrancheTwoUnfilled_isNotReportedAsUnseedable() {
        // The ordinary two-tranche shape mid-flight. It has a leg, so it is not "legless on the
        // legacy path" and must stay quiet.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("6"))));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("6"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("6")));

        service.reconcile("c", "run1");

        assertThat(logLines()).noneMatch(l -> l.contains("cannot seed legs"));
    }

    @Test
    void pendingExitConfirmed_booksEveryLegOutWithTheRow() {
        // A flatten exits the WHOLE position, so no leg can survive it. An OPEN leg on a CLOSED
        // position would falsify the invariant this task exists to produce -- and seeding turns
        // that from a backfill artefact into the normal outcome of every hard-trigger flatten
        // and webhook FULL exit.
        ExecutorPosition p = pendingExitPosition(50L, "ACME", new BigDecimal("100"),
                new BigDecimal("95"), "stop-1", "HARD_STOP", "exit-1", new BigDecimal("94"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(50L)).thenReturn(List.of(
                leg(10L, 50L, 1, "ord-1", "stop-1", new BigDecimal("6")),
                leg(11L, 50L, 2, "ord-2", "stop-2", new BigDecimal("4"))));
        // Broker no longer holds it and the exit order is no longer working -> confirmed close.

        service.reconcile("c", "run1");

        verify(positionRepo).close(eq(50L), any(), any(), eq("HARD_STOP"), any(), any());
        verify(legRepo).closeLeg(eq(10L), argThatComparesTo("94"), eq("HARD_STOP"), any());
        verify(legRepo).closeLeg(eq(11L), argThatComparesTo("94"), eq("HARD_STOP"), any());
    }

    @Test
    void pendingExitNotYetConfirmed_leavesTheLegsAlone() {
        // The broker still holds the position: nothing has exited, so no leg may be booked out.
        ExecutorPosition p = pendingExitPosition(51L, "ACME", new BigDecimal("100"),
                new BigDecimal("95"), "stop-1", "HARD_STOP", "exit-1", new BigDecimal("94"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(51L)).thenReturn(List.of(
                leg(10L, 51L, 1, "ord-1", "stop-1", new BigDecimal("6"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        service.reconcile("c", "run1");

        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
    }

    private ExecutorPositionLeg leg(long id, long positionId, int tranche, String entryOrderId,
            String stopOrderId, BigDecimal qty) {
        return new ExecutorPositionLeg(id, positionId, tranche, entryOrderId, stopOrderId, qty,
                ExecutorPositionLeg.OPEN, null, null, null);
    }

    /** A FILLED order exactly as the fill history reports it: role OTHER, because the history
     *  endpoint carries no bracket structure and the gateway guesses the role from the order
     *  type. The known stop_order_id is what identifies it as an exit leg. */
    private BrokerOrder filled(String orderId, String symbol, BigDecimal qty, BigDecimal price) {
        return new BrokerOrder(orderId, "ref-" + orderId, symbol, OrderRole.OTHER,
                OrderStatus.FILLED, qty, qty, price, null);
    }

    @Test
    void allLegsFilled_closesOnceWithoutEscalation() {
        // Structural replay of the prod incident: both tranches stopped out, the broker no
        // longer holds the position, and the book escalated instead of closing -- leaving a
        // phantom row that vetoed every new trade via MAX_POSITIONS.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));
        gateway.seedOrder(filled("stop-2", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        ArgumentCaptor<BigDecimal> exitPrice = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(1L), exitPrice.capture(), any(), eq("HARD_STOP"),
                eq("FILL"), any());
        assertThat(exitPrice.getValue()).isEqualByComparingTo("95");

        verify(legRepo).closeLeg(eq(10L), any(), eq("HARD_STOP"), any());
        verify(legRepo).closeLeg(eq(11L), any(), eq("HARD_STOP"), any());
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("TRANCHE2_DESYNC"));
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("LEG_QTY_DESYNC"));
        assertThat(survivors).isEmpty();
    }

    @Test
    void oneLegFilled_trimsAndKeepsPositionOpen() {
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(legRepo).closeLeg(eq(10L), argThatComparesTo("95"), eq("HARD_STOP"), any());
        verify(legRepo, never()).closeLeg(eq(11L), any(), any(), any());
        verify(positionRepo).recordTrim(eq(1L), argThatComparesTo("10"), eq(1));
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeastOnce()).insert(captor.capture());
        DecisionLog trim = captor.getAllValues().stream()
                .filter(d -> "TRIM".equals(d.action())).findFirst().orElseThrow();
        assertThat(trim.orderJson().path("qty_closed").asInt()).isEqualTo(10);
        assertThat(trim.orderJson().path("qty_remaining").asInt()).isEqualTo(10);
        assertThat(trim.orderJson().path("price").asDouble()).isEqualTo(95.0);
        assertThat(trim.orderJson().path("fraction").asDouble()).isEqualTo(0.5);
        assertThat(trim.orderJson().path("position_id").asLong()).isEqualTo(1L);
        assertThat(captor.getAllValues()).noneMatch(
                d -> "TRANCHE2_DESYNC".equals(d.reasonCode())
                        || "LEG_QTY_DESYNC".equals(d.reasonCode()));

        // The row stays OPEN and is handed on carrying the surviving quantity, not the stale one.
        assertThat(survivors).hasSize(1);
        assertThat(survivors.getFirst().qty()).isEqualByComparingTo("10");
        assertThat(survivors.getFirst().trimCount()).isEqualTo(1);

        // Not CRITICAL -- the position shrank on its own, which is a notice, not an incident.
        verify(telegram).notifyAlert(eq("ACME"), any(), eq("INFO"), any());
    }

    @Test
    void positionGoneWithoutAnyFill_closesAsReconcileGone() {
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo).close(eq(1L), any(), any(), eq("RECONCILE_GONE"), any(), any());
        verify(legRepo).closeLeg(eq(10L), any(), eq("RECONCILE_GONE"), any());
        verify(legRepo).closeLeg(eq(11L), any(), eq("RECONCILE_GONE"), any());
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("TRANCHE2_DESYNC"));
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("LEG_QTY_DESYNC"));
        assertThat(survivors).isEmpty();
    }

    @Test
    void fillHistoryUnavailable_doesNotCloseVanishedPosition() {
        // Same principle as the legless path: without the fill history a vanished position
        // cannot be told apart from one that closed at a price we never saw. Booking the
        // RECONCILE_GONE estimate anyway would invent a realized R for shares whose fate is
        // genuinely unknown this pass — the row stays OPEN and an operator has to look.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.filledOrdersThrows = new RuntimeException("history endpoint down");

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog esc = captor.getValue();
        assertThat(esc.action()).isEqualTo("ESCALATE");
        assertThat(esc.reasonCode()).isEqualTo("FILL_HISTORY_UNAVAILABLE");
        // The discriminator between "would have closed" and "would have resized" must be
        // queryable, not buried in the reasoning prose -- same field name convention as
        // escalateLegQtyDesync's book/legs/broker quantities.
        assertThat(esc.inputsSnapshot().path("withheld").asText()).isEqualTo("RECONCILE_GONE");
        assertThat(esc.inputsSnapshot().path("book_qty").asInt()).isEqualTo(20);
        assertThat(esc.inputsSnapshot().path("legs_qty").asInt()).isEqualTo(20);
        assertThat(esc.inputsSnapshot().has("broker_qty")).isFalse();
        assertThat(survivors).extracting(ExecutorPosition::id).containsExactly(1L);
    }

    @Test
    void brokerQtyDisagreesWithoutFill_escalatesWithBothQuantities() {
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("15"),
                new BigDecimal("100"), new BigDecimal("98"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeastOnce()).insert(captor.capture());
        DecisionLog esc = captor.getAllValues().stream()
                .filter(d -> "LEG_QTY_DESYNC".equals(d.reasonCode())).findFirst().orElseThrow();
        assertThat(esc.action()).isEqualTo("ESCALATE");
        // The legacy legless path keeps TRANCHE2_DESYNC: two different conditions must not share
        // one alarm name.
        assertThat(captor.getAllValues()).noneMatch(d -> "TRANCHE2_DESYNC".equals(d.reasonCode()));
        assertThat(esc.inputsSnapshot().path("book_qty").asInt()).isEqualTo(20);
        assertThat(esc.inputsSnapshot().path("broker_qty").asInt()).isEqualTo(15);
        assertThat(esc.inputsSnapshot().path("legs_qty").asInt()).isEqualTo(20);

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        assertThat(survivors).hasSize(1);
    }

    @Test
    void openLegQtyDivergesFromWorkingStop_syncsLegToTheBroker() {
        // Same philosophy updateMaintenance applies to the position's own qty: qty means shares
        // HELD. A leg whose stop order the broker still works with a different size must follow
        // the broker rather than drift the way the position row did.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("18"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("18"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(new BrokerOrder("stop-1", "ref-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.WORKING, new BigDecimal("8"), BigDecimal.ZERO, null, "ord-1"));
        gateway.seedOrder(new BrokerOrder("stop-2", "ref-2", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.WORKING, new BigDecimal("10"), BigDecimal.ZERO, null, "ord-2"));

        service.reconcile("c", "run-1");

        verify(legRepo).syncLegQty(eq(10L), argThatComparesTo("8"));
        verify(legRepo, never()).syncLegQty(eq(11L), any());
        // Legs now sum to the broker's 18 -> no desync escalation.
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("LEG_QTY_DESYNC"));
    }

    private static BigDecimal argThatComparesTo(String expected) {
        BigDecimal e = new BigDecimal(expected);
        return org.mockito.ArgumentMatchers.argThat(
                (BigDecimal actual) -> actual != null && actual.compareTo(e) == 0);
    }


    @Test
    void singleOpenLegBrokerHoldsLess_syncsQtyInsteadOfEscalating() {
        // Regression guard for the prod-verified QTY_SYNC path (2026-08-06: book claims 12, broker
        // holds 6) once the position HAS legs. With exactly one open leg the leg IS the position,
        // so the broker's smaller holding is unambiguously attributable -- syncing is right and
        // escalating would make the fixed bug unfixable again. Pinned WITH a leg present on
        // purpose: the older regression test only passes because its leg list is empty.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("12"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("12"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo).syncQty(1L, new BigDecimal("6"));
        verify(legRepo).syncLegQty(eq(10L), argThatComparesTo("6"));
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("LEG_QTY_DESYNC"));
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("TRANCHE2_DESYNC"));
        assertThat(survivors).hasSize(1);
        assertThat(survivors.getFirst().qty()).isEqualByComparingTo("6");
    }

    @Test
    void singleOpenLegBrokerHoldsLessButFillHistoryUnavailable_escalatesInsteadOfSyncingDown() {
        // Same broker state as singleOpenLegBrokerHoldsLess_syncsQtyInsteadOfEscalating (a single
        // leg, broker holding less) but with the fill-history channel broken. Without that
        // evidence a smaller broker quantity cannot be told apart from an unobserved stop fill,
        // and syncing the qty down quietly would converge the book with no TRIM row and no
        // realized R for the missing shares -- exactly the kind of silent rewrite this task
        // exists to stop. Escalate and leave the leg/position quantities untouched instead.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("12"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("12"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.filledOrdersThrows = new RuntimeException("history endpoint down");

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo, never()).syncQty(anyLong(), any());
        verify(legRepo, never()).syncLegQty(anyLong(), any());
        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog esc = captor.getValue();
        assertThat(esc.action()).isEqualTo("ESCALATE");
        assertThat(esc.reasonCode()).isEqualTo("FILL_HISTORY_UNAVAILABLE");
        assertThat(esc.inputsSnapshot().path("withheld").asText()).isEqualTo("QTY_SYNC_SHORTFALL");
        assertThat(esc.inputsSnapshot().path("book_qty").asInt()).isEqualTo(12);
        assertThat(esc.inputsSnapshot().path("legs_qty").asInt()).isEqualTo(12);
        assertThat(esc.inputsSnapshot().path("broker_qty").asInt()).isEqualTo(6);
        assertThat(survivors).extracting(ExecutorPosition::qty)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("12"));
    }

    @Test
    void fillHistoryUnavailable_doesNotResizeLegFromItsWorkingStopQtyEither() {
        // syncLegQuantities runs before any fillHistoryAvailable gate and resizes a leg straight
        // from its WORKING stop order's reported quantity -- but a stop that partially filled
        // reports a reduced working quantity, which is indistinguishable from an unobserved
        // partial fill when the history call failed. Left ungated, this would silently shrink the
        // leg (SYNC/LEG_QTY_SYNC) in the very same pass that then escalates
        // FILL_HISTORY_UNAVAILABLE -- and it would also erase the evidence the shortfall gate
        // needs: once the leg is resynced to the broker's smaller number, brokerShortfallAttributableToOneLeg
        // sees no shortfall left to report.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("12"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("12"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(new BrokerOrder("stop-1", "ref-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.WORKING, new BigDecimal("6"), BigDecimal.ZERO, null, "ord-1"));
        gateway.filledOrdersThrows = new RuntimeException("history endpoint down");

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(legRepo, never()).syncLegQty(anyLong(), any());
        verify(positionRepo, never()).syncQty(anyLong(), any());
        verify(decisionRepo).insert(argThat(d -> "ESCALATE".equals(d.action())
                && "FILL_HISTORY_UNAVAILABLE".equals(d.reasonCode())));
        assertThat(survivors).hasSize(1);
        assertThat(survivors.getFirst().qty()).isEqualByComparingTo("12");
    }

    @Test
    void singleOpenLegBrokerHoldsMore_stillEscalates() {
        // Only a SMALLER broker holding is attributable to the leg (a tranche that never filled,
        // a partial). More shares than the book knows about is unexplained capital -- escalate.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("6"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("12"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        service.reconcile("c", "run-1");

        verify(decisionRepo, atLeastOnce()).insert(argThatReasonCodeIs("LEG_QTY_DESYNC"));
        verify(positionRepo, never()).syncQty(anyLong(), any());
    }

    @Test
    void legQtySyncIgnoresNonPositiveBrokerQty() {
        // executor_position_leg carries CHECK (qty > 0): writing 0 aborts the transaction. A leg
        // that really reached zero has to be CLOSED by a fill path, never resized to nothing.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(new BrokerOrder("stop-1", "ref-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.WORKING, BigDecimal.ZERO, BigDecimal.ZERO, null, "ord-1"));

        service.reconcile("c", "run-1");

        verify(legRepo, never()).syncLegQty(anyLong(), any());
    }

    @Test
    void reconcileTrim_nullsTheFilledTranchesStopColumn() {
        // The 3-arg recordTrim does not touch the stop columns, so the column for the tranche that
        // just filled would keep naming an order the broker no longer has -- and the ratchet would
        // then address it by name and escalate on a state we created ourselves.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo).clearStopLeg(1L, "stop-1");
        // The survivor handed on in the same pass must not still name the dead leg either.
        assertThat(survivors.getFirst().stopOrderId()).isNull();
        assertThat(survivors.getFirst().tranche2StopOrderId()).isEqualTo("stop-2");
    }

    @Test
    void legFilledWithoutUsablePrice_stillTrimsAndRecordsANullPrice() {
        // The shares are gone whether or not the broker reported what they went for. Leaving the
        // book claiming them is the phantom-position bug; a fabricated price would fabricate a
        // realized R. So: trim on the known quantity, record price null, warn.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), null));

        service.reconcile("c", "run-1");

        verify(positionRepo).recordTrim(eq(1L), argThatComparesTo("10"), eq(1));
        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeastOnce()).insert(captor.capture());
        DecisionLog trim = captor.getAllValues().stream()
                .filter(d -> "TRIM".equals(d.action())).findFirst().orElseThrow();
        assertThat(trim.orderJson().path("qty_closed").asInt()).isEqualTo(10);
        assertThat(trim.orderJson().path("price").isNull()).isTrue();
        assertThat(trim.inputsSnapshot().has("fill_price_available")).isTrue();
        assertThat(trim.inputsSnapshot().path("fill_price_available").asBoolean()).isFalse();
    }

    @Test
    void allLegsFilledWhileBrokerStillHoldsShares_escalatesOrphanInTheSamePass() {
        // The row closes on observed fills (that is the whole point -- a lagging position feed
        // must not push a stopped-out position back into an escalation), but a holding the broker
        // still reports is unmanaged capital and has to be flagged in THIS pass, not one full
        // cycle later once the row has left `findOpen`.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));
        gateway.seedOrder(filled("stop-2", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo).close(eq(1L), any(), any(), eq("HARD_STOP"), eq("FILL"), any());
        verify(decisionRepo, atLeastOnce()).insert(argThatReasonCodeIs("ORPHAN_POSITION"));
        verify(telegram).notifyAlert(eq("ACME"), eq("ORPHAN_POSITION"), eq("CRITICAL"), any());
        assertThat(survivors).isEmpty();

        // POST_LOOP, not PRE_LOOP: this is the much stronger signal -- a booking THIS run just
        // made may be wrong, because the broker still reports shares for the row we closed.
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeastOnce()).insert(logCaptor.capture());
        DecisionLog orphan = logCaptor.getAllValues().stream()
                .filter(l -> "ORPHAN_POSITION".equals(l.reasonCode()))
                .findFirst().orElseThrow();
        assertThat(orphan.inputsSnapshot().path("phase").asString()).isEqualTo("POST_LOOP");
    }

    @Test
    void survivingPositionIsNotReportedAsAnOrphan() {
        // Idempotence guard for the post-loop scan: a position that stays OPEN is still known.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("98"), null));

        service.reconcile("c", "run-1");

        verify(decisionRepo, never()).insert(argThatReasonCodeIs("ORPHAN_POSITION"));
        verify(telegram, never()).notifyAlert(any(), eq("ORPHAN_POSITION"), any(), any());
    }


    @Test
    void reconcileTrim_recordsTheStopLegCollapseSoTheSurvivorRatchetsOnOneLeg() {
        // Nulling the dead stop id is only half the bookkeeping: a two-tranche row that names one
        // stop leg and does not say why is a state our own trim created, and the legacy
        // column-based ratchet path -- still live for positions that have no leg rows -- escalates
        // exactly that as TRANCHE_RATCHET_UNSUPPORTED. Recording the collapse is what explains it.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo).markStopLegsCollapsed(1L);
        assertThat(p.stopLegsCollapsed()).isFalse();                   // before the trim
        assertThat(survivors.getFirst().stopLegsCollapsed()).isTrue();
        assertThat(survivors.getFirst().stopOrderId()).isNull();       // the dead leg is unnamed
        assertThat(survivors.getFirst().tranche2StopOrderId()).isEqualTo("stop-2");
    }

    @Test
    void reconcileTrim_leavesASurvivorTheRealRatchetMovesOnItsSurvivingLegAlone() {
        // Replaces a helper that re-implemented StopRatchetService's routing expression verbatim
        // and then asserted against the copy. What matters is not which branch the ratchet picks
        // but the OUTCOME on the real service: the trimmed position gets its surviving leg moved,
        // by name, and nothing escalates. The leg reconcile just closed (stop-1) is not addressed
        // at all -- that, and not a special case inside the ratchet, is why the ordinary "the leg
        // is gone because it filled" case never produces a STOP_LEG_MISSING row.
        ExecutorPosition p = new ExecutorPosition(1L, "c", "ACME", "BUY", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 2, null, List.of(),
                "sig-1", "agent", "2026-07-01", null, "OPEN", "ord-1", new BigDecimal("110"),
                BigDecimal.ZERO, 0, null, null, null, null, "stop-1", null, null, "ord-2", "stop-2",
                0, null, null, null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(legRepo).closeLeg(eq(10L), any(), eq("HARD_STOP"), any());
        // The mocked repository does not apply that close, so the post-reconcile leg book is
        // stated here: it is what a real repository would return to the ratchet's own
        // findOpenByPosition call one pipeline step later, in this same pass.
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        org.mockito.Mockito.clearInvocations(decisionRepo);

        StopRatchetService ratchet = new StopRatchetService(gateway, positionRepo, legRepo,
                decisionRepo, ruleVersions, new StopRatchetGuard(), mapper, executorNotifier,
                3.0, 1, 0L, 0L, BigDecimal.ZERO);
        ratchet.ratchet(survivors, Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run-1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isEqualTo("stop-2");
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("104");
        ArgumentCaptor<DecisionLog> ratchetLogs = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(ratchetLogs.capture());
        assertThat(ratchetLogs.getValue().action()).isEqualTo("MODIFY_STOP");
    }

    @Test
    void reconcileTrim_doesNotRecordACollapseWhileBothStopLegsAreStillNamed() {
        // The flag has exactly one job: explain why a two-tranche position names only one stop
        // leg. A trim that leaves both columns populated has nothing to explain, and claiming a
        // collapse would send a genuinely two-legged position down the single-leg ratchet.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", null, new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        // Leg 1 has no stop id of its own; its fill is matched through its entry order instead,
        // so neither stop column is cleared.
        gateway.seedOrder(new BrokerOrder("tp-1", "ref-1", "ACME", OrderRole.TAKE_PROFIT,
                OrderStatus.FILLED, new BigDecimal("10"), new BigDecimal("10"),
                new BigDecimal("112"), "ord-1"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        verify(positionRepo).recordTrim(eq(1L), argThatComparesTo("10"), eq(1));
        verify(positionRepo, never()).markStopLegsCollapsed(anyLong());
        assertThat(survivors.getFirst().stopLegsCollapsed()).isFalse();
        assertThat(survivors.getFirst().stopOrderId()).isEqualTo("stop-1");
        assertThat(survivors.getFirst().tranche2StopOrderId()).isEqualTo("stop-2");
    }

    @Test
    void pricelessTrim_telegramTextDoesNotInterpolateNull() {
        // Operator-facing text: "(10 shares at null)" is noise that reads like a bug in the alert.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), null));

        service.reconcile("c", "run-1");

        ArgumentCaptor<String> thesis = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyAlert(eq("ACME"), eq("POSITION_TRIMMED"), eq("INFO"), thesis.capture());
        assertThat(thesis.getValue()).doesNotContain("null");
        assertThat(thesis.getValue()).contains("10 shares");
        assertThat(thesis.getValue()).contains("no fill price");
    }

    @Test
    void trimWithAFillPriceStillNamesItInTheTelegramText() {
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        service.reconcile("c", "run-1");

        ArgumentCaptor<String> thesis = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyAlert(eq("ACME"), eq("POSITION_TRIMMED"), eq("INFO"), thesis.capture());
        assertThat(thesis.getValue()).contains("10 shares at 95");
    }

    // ---------------------------------------------------------------------------------------
    // A stop fill no leg claims (UNCLAIMED_STOP_FILL). Seeding only ever sees a WORKING stop, so
    // a tranche whose entry AND stop both fill between two nightly passes never becomes a leg --
    // and matchLegFills iterates existing legs, so nothing would ever look at that fill.
    // ---------------------------------------------------------------------------------------

    @Test
    void tranche2RoundTripInsideOnePass_escalatesInsteadOfEndingSilently() {
        // Tranche 2's entry filled and its stop filled between two passes. Seeding sees no
        // working stop for it, so no leg is created; leg 1 is untouched and agrees with the
        // broker's holding. Before the leg rewrite this raised TRANCHE2_DESYNC; without this
        // branch the pass ends at updateMaintenance with no TRIM, no realized R and no alarm.
        // Book qty 12 (both tranches were booked), one open leg of 6, broker holds 6. Without
        // the escalation branch this lands in updateMaintenance, which syncs qty 12 -> 6: six
        // shares written out of the book with no TRIM row and no realized R.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("12"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("6"))));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("6"))));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));
        gateway.seedOrder(workingStop("stop-1", "ACME", new BigDecimal("6")));
        // The tranche-2 stop the position itself recorded, reported FILLED by the history.
        gateway.seedOrder(filled("stop-2", "ACME", new BigDecimal("6"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeastOnce()).insert(logCaptor.capture());
        DecisionLog escalation = logCaptor.getAllValues().stream()
                .filter(l -> "UNCLAIMED_STOP_FILL".equals(l.reasonCode()))
                .findFirst().orElseThrow();
        assertThat(escalation.action()).isEqualTo("ESCALATE");
        assertThat(escalation.symbol()).isEqualTo("ACME");
        // Queryable, not prose: which stop filled, and what the book still believes it holds.
        assertThat(escalation.inputsSnapshot().path("filled_stop_order_ids").asString())
                .isEqualTo("stop-2");
        assertThat(escalation.inputsSnapshot().path("open_leg_count").asInt()).isEqualTo(1);
        assertThat(escalation.inputsSnapshot().path("legs_qty").asInt()).isEqualTo(6);
        assertThat(escalation.inputsSnapshot().path("book_qty").asInt()).isEqualTo(12);
        assertThat(escalation.inputsSnapshot().path("broker_qty").asInt()).isEqualTo(6);

        // The row is left completely untouched -- no close, no trim, and above all no qty sync
        // computed from legs the evidence has just contradicted.
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(positionRepo, never()).syncQty(anyLong(), any());
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
        assertThat(survivors).containsExactly(p);
    }

    @Test
    void stopFillItsOwnLegClaims_isNotReportedAsUnclaimed() {
        // The negative half: the ordinary stop-out must not also raise UNCLAIMED_STOP_FILL, or
        // the alarm means nothing. Same fill, but a leg names the order, so it is claimed.
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(
                leg(10L, 1L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 1L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));
        gateway.seedOrder(filled("stop-2", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        service.reconcile("c", "run-1");

        verify(decisionRepo, never()).insert(argThatReasonCodeIs("UNCLAIMED_STOP_FILL"));
    }

    // ---------------------------------------------------------------------------------------
    // An interrupted close finishes itself. Nothing in the executor is transactional, so a crash
    // between closeLeg and bookClose leaves CLOSED legs under an OPEN row -- previously a
    // permanent TRANCHE2_DESYNC loop that could never be re-legged either.
    // ---------------------------------------------------------------------------------------

    private ExecutorPositionLeg closedLeg(long id, long positionId, int tranche, String stopOrderId,
            BigDecimal qty, BigDecimal exitPrice, String exitReason) {
        return new ExecutorPositionLeg(id, positionId, tranche, "ord-" + tranche, stopOrderId, qty,
                ExecutorPositionLeg.CLOSED, exitPrice, exitReason, "2026-07-08T11:00:00Z");
    }

    @Test
    void openRowWithOnlyClosedLegs_completesTheCloseInsteadOfEscalatingForever() {
        ExecutorPosition p = twoTranchePosition(1L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        // The broker no longer holds it -- which is precisely why re-seeding cannot repair this
        // state: seedLegsFromWorkingStops returns on bp == null before it looks at any tranche.
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of());
        when(legRepo.findByPosition(1L)).thenReturn(List.of(
                closedLeg(10L, 1L, 1, "stop-1", new BigDecimal("10"), new BigDecimal("94"), "HARD_STOP"),
                closedLeg(11L, 1L, 2, "stop-2", new BigDecimal("10"), new BigDecimal("96"), "HARD_STOP")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-1").survivors();

        ArgumentCaptor<BigDecimal> exitPrice = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(1L), exitPrice.capture(), any(), eq("HARD_STOP"),
                eq("FILL"), any());
        // Quantity-weighted over the legs' OWN recorded fills: (94*10 + 96*10) / 20.
        assertThat(exitPrice.getValue()).isEqualByComparingTo("95");
        // Nothing is re-closed and nothing is escalated -- the legs were already booked out.
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
        verify(decisionRepo, never()).insert(argThatReasonCodeIs("TRANCHE2_DESYNC"));
        assertThat(survivors).isEmpty();
        assertThat(logLines()).anyMatch(l -> l.contains("a close was interrupted"));
    }

    @Test
    void openRowWithClosedLegsLackingFillProvenance_doesNotClaimFILL() {
        // A leg closed as RECONCILE_GONE carries a resolved estimate, not a broker fill. The
        // completed close must not relabel that as FILL -- it re-derives the exit instead.
        ExecutorPosition p = twoTranchePosition(2L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(2L)).thenReturn(List.of());
        when(legRepo.findByPosition(2L)).thenReturn(List.of(
                closedLeg(20L, 2L, 1, "stop-1", new BigDecimal("20"), new BigDecimal("94"),
                        "RECONCILE_GONE")));

        service.reconcile("c", "run-1");

        verify(positionRepo).close(eq(2L), any(), any(), eq("RECONCILE_GONE"),
                eq("RECONCILE_GONE"), any());
    }

    @Test
    void openRowWithOnlyCancelledLegs_isNotTreatedAsAnInterruptedClose() {
        // CANCELLED means the shares were never held, so there is no close to complete. The row
        // keeps the legacy legless behaviour rather than being booked out on nothing.
        ExecutorPosition p = twoTranchePosition(3L, "ACME", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(3L)).thenReturn(List.of());
        when(legRepo.findByPosition(3L)).thenReturn(List.of(
                new ExecutorPositionLeg(30L, 3L, 1, "ord-1", "stop-1", new BigDecimal("20"),
                        ExecutorPositionLeg.CANCELLED, null, null, null)));

        service.reconcile("c", "run-1");

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(decisionRepo, atLeastOnce()).insert(argThatReasonCodeIs("TRANCHE2_DESYNC"));
    }

    // -------------------------------------------------------------------
    // A rejection on the position/order read is a verdict, not an outage.
    // -------------------------------------------------------------------

    @Test
    void aRejectedPositionRead_isFiledAsBrokerRejectedNotAsAnOutage() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-1", "stop-1", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.rejectReadsWith = new BrokerRejectedException(
                "agora order rejected [UNKNOWN_CONNECTION]: no such connection",
                "UNKNOWN_CONNECTION", List.of());

        ReconcileService.ReconcileResult result = service.reconcile("c", "run1");

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.reasonCode()).isNotEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.inputsSnapshot().path("reject_code").asString())
                .isEqualTo("UNKNOWN_CONNECTION");
        // Same book-untouched outcome as an outage -- only the name an operator sees changes.
        assertThat(result.survivors()).containsExactly(p);
        assertThat(result.unfilledIds()).isEmpty();
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void aRealOutageOnThePositionRead_stillFilesAsBrokerUnavailable() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-1", "stop-1", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.unavailable = true;

        service.reconcile("c", "run1");

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
    }

    // ---------------------------------------------------------------------------------------
    // Task 7: entry_filled_at, and the MARK estimate chosen by the PRODUCER of the exit.
    // ---------------------------------------------------------------------------------------

    /** Copy of a fixture with an explicit broker stop — the price the protective leg rests at. */
    private static ExecutorPosition withBrokerStop(ExecutorPosition p, BigDecimal brokerStop) {
        return withFillState(p, brokerStop, p.entryFilledAt());
    }

    /** Copy of a fixture carrying BOTH columns this task threads through the reconcile rebuilds. */
    private static ExecutorPosition withFillState(ExecutorPosition p, BigDecimal brokerStop,
            String entryFilledAt) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), brokerStop, entryFilledAt);
    }

    /** Test 27. A protective leg filled with no reported price: the estimate must be the price the
     *  LEG actually rested at, not the logical stop it never sat on — and it must be labelled MARK,
     *  because the STOP_LOSS arm pre-stamps "FILL" before the shared tail runs.
     *  Mutation: keep p.activeStop() as the estimate, or let the pre-stamped "FILL" survive. */
    @Test
    void markFallbackUsesBrokerStopWhenAStopLegFilledWithoutPrice() {
        ExecutorPosition p = withBrokerStop(openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-1", "stop-1", null, null), new BigDecimal("93.00"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        // FILLED stop leg with a null avgFillPrice — exactly what the broker's history returns when
        // it reports the fill but not the price.
        gateway.seedOrder(new BrokerOrder("stop-1", "ref-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, null, "brk-1"));

        service.reconcile("c", "run1");

        verify(positionRepo).close(eq(1L), argThatComparesTo("93.00"), any(),
                eq("HARD_STOP"), eq("MARK"), any());
    }

    /** Test 27, null-broker-stop half: a row opened before the broker_stop column has no broker
     *  stop and must fall back to active_stop, exactly as today. Mutation: NPE, or book zero. */
    @Test
    void fallsBackToActiveStopWhenBrokerStopNull() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-1", "stop-1", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("stop-1", "ref-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, null, "brk-1"));

        service.reconcile("c", "run1");

        verify(positionRepo).close(eq(1L), argThatComparesTo("95"), any(),
                eq("HARD_STOP"), eq("MARK"), any());
    }

    /** Test 27, the closePositionFromLegs site: EVERY leg filled, none of them reported a price.
     *  Same rule, other code path — closePositionFromLegs' own fallback.
     *  Mutation: keep `bp != null ? bp.marketPrice() : p.activeStop()` unchanged. */
    @Test
    void allLegsFilledWithoutPriceBooksTheBrokerStopEstimate() {
        ExecutorPosition p = withBrokerStop(openPosition(10L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-10", "stop-10", null, null), new BigDecimal("93.00"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(10L)).thenReturn(List.of(
                leg(1L, 10L, 1, "brk-10", "stop-10", BigDecimal.TEN)));
        // FILLED, but avgFillPrice null — weightedFillPrice() then has nothing to average.
        gateway.seedOrder(new BrokerOrder("stop-10", "ref-10", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, null, "brk-10"));

        service.reconcile("c", "run1");

        verify(positionRepo).close(eq(10L), argThatComparesTo("93.00"), any(),
                eq("HARD_STOP"), eq("MARK"), any());
    }

    /** Test 27b. finalizePendingExitOrKeep's reason comes from pending_exit_reason, written only by
     *  the MARKET-order exit paths. Those exits cancel the protective leg before closing, so the
     *  broker stop is not what they filled at — substituting it here would be a fabricated price.
     *  The distinct MARK_FLATTEN label is what lets the acceptance measurement exclude these rows.
     *  Mutation: substitute brokerStop here, or keep the plain "MARK" label. */
    @Test
    void pendingExitFinalizeKeepsActiveStopEvenWhenReasonIsHardStop() {
        ExecutorPosition p = withBrokerStop(
                pendingExitPosition(2L, "ACME", new BigDecimal("100"), new BigDecimal("95"),
                        "stop-1", "HARD_STOP", "close-1", null),
                new BigDecimal("93.00"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        // Broker no longer holds it and the exit order is not working -> confirmed gone.

        service.reconcile("c", "run1");

        verify(positionRepo).close(eq(2L), argThatComparesTo("95"), any(),
                eq("HARD_STOP"), eq("MARK_FLATTEN"), any());
    }

    /** Test 27c. A take-profit leg filled with no reported price must NEVER be booked at the stop:
     *  that would record a winning exit at a losing price. It keeps the market-price/active-stop
     *  fallback of the shared tail, labelled MARK.
     *  Mutation: treat the TAKE_PROFIT arm as a protective leg. */
    @Test
    void takeProfitLegWithoutFillPriceIsNeverBookedAtBrokerStop() {
        ExecutorPosition p = withBrokerStop(openPosition(3L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-3", "tp-3", null, null), new BigDecimal("93.00"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("tp-3", "ref-3", "ACME", OrderRole.TAKE_PROFIT,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, null, "brk-3"));

        service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> exitPrice = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(3L), exitPrice.capture(), any(),
                eq("TAKE_PROFIT"), eq("MARK"), any());
        assertThat(exitPrice.getValue()).isEqualByComparingTo("95");   // active_stop, NOT 93.00
    }

    /** Test 28. entry_filled_at is written on the CONJUNCTION: the broker holds the symbol AND no
     *  entry order is still working. Mutation: write on bp != null alone — a foreign or orphaned
     *  holding in the same symbol satisfies that, and a partially-filled GTD entry would be
     *  declared filled while its remainder is still working. */
    @Test
    void entryFilledAtSetOnceWhenBrokerHoldsAndEntryNotPending() {
        ExecutorPosition p = openPosition(4L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-4", "stop-4", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("101"), 1));

        service.reconcile("c", "run1");

        verify(positionRepo).markEntryFilled(4L, NOW);
    }

    /** Test 28. No broker holding -> never written. */
    @Test
    void entryFilledAtNotSetForUnfilledEntry() {
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-5", "stop-5", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        // The GTD entry is still working and the broker holds nothing.
        gateway.seedOrder(new BrokerOrder("brk-5", "ref-5", "ACME", OrderRole.ENTRY,
                OrderStatus.WORKING, BigDecimal.TEN, BigDecimal.ZERO, null, null));

        service.reconcile("c", "run1");

        verify(positionRepo, never()).markEntryFilled(anyLong(), any());
    }

    /** Test 28, the case that reddens the `bp != null` mutation: the broker DOES hold the symbol,
     *  but the position's own entry order is still working (a partial fill). Not filled. */
    @Test
    void entryFilledAtNotSetWhileEntryOrderStillWorkingDespiteBrokerHolding() {
        ExecutorPosition p = openPosition(6L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-6", "stop-6", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("4"),
                new BigDecimal("100"), new BigDecimal("101"), 1));
        gateway.seedOrder(new BrokerOrder("brk-6", "ref-6", "ACME", OrderRole.ENTRY,
                OrderStatus.PARTIALLY_FILLED, BigDecimal.TEN, new BigDecimal("4"), null, null));

        service.reconcile("c", "run1");

        verify(positionRepo, never()).markEntryFilled(anyLong(), any());
    }

    /** Test 28. Already stamped -> the reconciler does not write again (the repository COALESCE is
     *  the second guard, tested in Task 2). Mutation: drop the `entryFilledAt() == null` check. */
    @Test
    void entryFilledAtNotRewrittenOnceStamped() {
        ExecutorPosition base = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-7", "stop-7", null, null);
        ExecutorPosition p = new ExecutorPosition(base.id(), base.connection(), base.symbol(),
                base.side(), base.qty(), base.entryPrice(), base.initialStop(), base.activeStop(),
                base.tranche(), base.rValue(), base.killCriteria(), base.sourceSignalId(),
                base.sourceAgent(), base.entryDate(), base.mfe(), base.status(),
                base.brokerOrderId(), base.highestPrice(), base.mfeR(), base.softConfirmCount(),
                base.exitPrice(), base.realizedR(), base.exitReason(), base.closedAt(),
                base.stopOrderId(), base.sector(), base.entryDayHigh(), base.tranche2OrderId(),
                base.tranche2StopOrderId(), base.trimCount(), base.lowestPrice(),
                base.entryExpiresAt(), base.submittedLimitPrice(), base.pendingExitReason(),
                base.exitOrderId(), base.pendingExitFillPrice(), base.stopLegsCollapsed(),
                null, "2026-07-01T09:00:00Z");
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("101"), 1));

        service.reconcile("c", "run1");

        verify(positionRepo, never()).markEntryFilled(anyLong(), any());
    }

    /** Test 29b. The five positional rebuilds inside ReconcileService must forward broker_stop and
     *  entry_filled_at — a null at any of them silently blanks the broker leg for the rest of the
     *  pass, and the survivor the pipeline then ratchets would compute its monotonic floor from
     *  active_stop instead of the real leg price.
     *  Mutation: pass null at any of the five rebuild sites. */
    @Test
    void reconcileRebuiltPositionPreservesBrokerStopAndEntryFilledAt() {
        ExecutorPosition base = openPosition(8L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-8", "stop-8", null, null);
        ExecutorPosition p = new ExecutorPosition(base.id(), base.connection(), base.symbol(),
                base.side(), base.qty(), base.entryPrice(), base.initialStop(), base.activeStop(),
                base.tranche(), base.rValue(), base.killCriteria(), base.sourceSignalId(),
                base.sourceAgent(), base.entryDate(), base.mfe(), base.status(),
                base.brokerOrderId(), base.highestPrice(), base.mfeR(), base.softConfirmCount(),
                base.exitPrice(), base.realizedR(), base.exitReason(), base.closedAt(),
                base.stopOrderId(), base.sector(), base.entryDayHigh(), base.tranche2OrderId(),
                base.tranche2StopOrderId(), base.trimCount(), base.lowestPrice(),
                base.entryExpiresAt(), base.submittedLimitPrice(), base.pendingExitReason(),
                base.exitOrderId(), base.pendingExitFillPrice(), base.stopLegsCollapsed(),
                new BigDecimal("93.00"), "2026-07-01T09:00:00Z");
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        // A broker holding whose avg entry price differs from the book forces the ENTRY_PRICE_SYNC
        // rebuild AND the updateMaintenance rebuild on one pass.
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("99.50"), new BigDecimal("101"), 1));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).brokerStop()).isEqualByComparingTo("93.00");
        assertThat(survivors.get(0).entryFilledAt()).isEqualTo("2026-07-01T09:00:00Z");
        // And the maintenance write forwards it rather than blanking it.
        verify(positionRepo).updateMaintenance(eq(8L), any(), any(), anyInt(), any(), isNull(),
                argThatComparesTo("93.00"));
    }

    /** Test 29. Regression: leg seeding is id/qty-based and completely indifferent to the
     *  active_stop / broker_stop divergence. Mutation: make seeding read either stop. */
    @Test
    void legSeedingIsUnaffectedByBrokerStop() {
        ExecutorPosition p = withBrokerStop(openPosition(9L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-9", "stop-9", null, null), new BigDecimal("80.00"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("101"), 1));
        gateway.seedOrder(workingStop("stop-9", "ACME", BigDecimal.TEN));

        service.reconcile("c", "run1");

        ExecutorPositionLeg seeded = captureSeededLegs().getValue();
        assertThat(seeded.positionId()).isEqualTo(9L);
        assertThat(seeded.tranche()).isEqualTo(1);
        assertThat(seeded.stopOrderId()).isEqualTo("stop-9");
        assertThat(seeded.qty()).isEqualByComparingTo("10");
    }

    /** Test 29b, the withTrim rebuild. A partial leg exit rebuilds the row positionally; blanking
     *  the two new components there hands the maintenance pipeline a survivor whose protective leg
     *  price and fill stamp have silently vanished for the rest of the pass.
     *  Mutation: pass null instead of p.brokerStop(), p.entryFilledAt() in withTrim. */
    @Test
    void trimRebuiltPositionPreservesBrokerStopAndEntryFilledAt() {
        ExecutorPosition p = withFillState(
                twoTranchePosition(60L, "ACME", new BigDecimal("20"),
                        new BigDecimal("100"), new BigDecimal("95")),
                new BigDecimal("93.00"), "2026-07-01T09:00:00Z");
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(legRepo.findOpenByPosition(60L)).thenReturn(List.of(
                leg(10L, 60L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 60L, 2, "ord-2", "stop-2", new BigDecimal("10"))));
        // Broker holds exactly the surviving tranche, and at the booked entry price, so neither
        // QTY_SYNC nor ENTRY_PRICE_SYNC runs: withTrim is the only rebuild under test here.
        gateway.seedPosition(new BrokerPosition("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("98"), null));
        gateway.seedOrder(filled("stop-1", "ACME", new BigDecimal("10"), new BigDecimal("95")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).recordTrim(eq(60L), argThatComparesTo("10"), eq(1));
        assertThat(survivors).hasSize(1);
        assertThat(survivors.getFirst().brokerStop()).isEqualByComparingTo("93.00");
        assertThat(survivors.getFirst().entryFilledAt()).isEqualTo("2026-07-01T09:00:00Z");
        // The trim path hands its own rebuild straight on as the survivor — it does not go through
        // updateMaintenance — so the survivor IS the withTrim output under assertion here.
    }

    /** Test 29b, the withQty rebuild. The book-follows-broker quantity sync rebuilds the row too.
     *  Mutation: pass null instead of p.brokerStop(), p.entryFilledAt() in withQty. */
    @Test
    void qtySyncRebuiltPositionPreservesBrokerStopAndEntryFilledAt() {
        ExecutorPosition p = withFillState(
                unfilledTranche2Position(61L, "SYNA", new BigDecimal("12")),
                new BigDecimal("93.00"), "2026-07-01T09:00:00Z");
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        // Broker holds 6 of the booked 12 at the booked entry price -> QTY_SYNC only.
        gateway.seedPosition(new BrokerPosition("SYNA", "BUY", new BigDecimal("6"),
                new BigDecimal("100"), new BigDecimal("104"), null));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1").survivors();

        verify(positionRepo).syncQty(61L, new BigDecimal("6"));
        assertThat(survivors).hasSize(1);
        assertThat(survivors.getFirst().qty()).isEqualByComparingTo("6");
        assertThat(survivors.getFirst().brokerStop()).isEqualByComparingTo("93.00");
        assertThat(survivors.getFirst().entryFilledAt()).isEqualTo("2026-07-01T09:00:00Z");
        verify(positionRepo).updateMaintenance(eq(61L), any(), any(), anyInt(), any(), isNull(),
                argThatComparesTo("93.00"));
    }

    /** Test 29b, the RECONCILE_GONE entry-price-sync rebuild. That copy becomes `effective`, the
     *  record the close is booked and NOTIFIED from — the only place it is observable from outside,
     *  and the reason a blanked rebuild there is a real defect rather than dead state.
     *  Mutation: pass null instead of p.brokerStop(), p.entryFilledAt() at the sync rebuild. */
    @Test
    void reconcileGoneSyncRebuiltPositionPreservesBrokerStopAndEntryFilledAt() {
        ExecutorPosition p = withFillState(
                openPosition(62L, "SYNG", "BUY", new BigDecimal("100.00"),
                        new BigDecimal("67.97"), "brk-62", "stop-62", null, null),
                new BigDecimal("66.00"), "2026-07-01T09:00:00Z");
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        // Gone from the broker, but its real fills are in the closed-position history -> the
        // matched branch syncs the entry price and rebuilds the record it books from.
        gateway.seedClosedPosition(new BrokerClosedPosition("SYNG", new BigDecimal("61.78"),
                new BigDecimal("61.53"), new BigDecimal("-0.25"), "sig-1"));

        service.reconcile("c", "run1");

        verify(positionRepo).syncEntryPrice(62L, new BigDecimal("61.78"));
        ArgumentCaptor<ExecutorPosition> booked = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(executorNotifier).notifyExit(booked.capture(), eq("RECONCILE_GONE"), any(), any(), any());
        assertThat(booked.getValue().entryPrice()).isEqualByComparingTo("61.78");  // the rebuild ran
        assertThat(booked.getValue().brokerStop()).isEqualByComparingTo("66.00");
        assertThat(booked.getValue().entryFilledAt()).isEqualTo("2026-07-01T09:00:00Z");
    }
}
