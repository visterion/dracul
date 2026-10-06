package de.visterion.dracul.executor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static de.visterion.dracul.executor.SavingsFixtures.filledParent;
import static de.visterion.dracul.executor.SavingsFixtures.filledStop;
import static de.visterion.dracul.executor.SavingsFixtures.liveParent;
import static de.visterion.dracul.executor.SavingsFixtures.liveStop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-06 §6.1, §12 "Reconcile" + R3 additions. TECHA: 10 @ 100, leg-1 stop "stop-1",
 *  the November add (9 @ 112.20, parent "brk-9", child "child-9") in flight. */
class ReconcileServiceSavingsTest {

    static final Instant NOW = Instant.parse("2026-11-03T23:00:00Z");

    final FakeExecutionGateway gateway = new FakeExecutionGateway();
    final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    final CooldownRepository cooldownRepo = mock(CooldownRepository.class);
    final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    final TelegramNotifier telegram = mock(TelegramNotifier.class);
    final ExecutorNotifier executorNotifier = mock(ExecutorNotifier.class);
    final InMemorySavingsPlanRepository savingsRepo = new InMemorySavingsPlanRepository();
    final ObjectMapper mapper = new ObjectMapper();
    ListAppender<ILoggingEvent> appender;
    ReconcileService service;
    ExecutorPosition position;
    ExecutorPositionLeg leg;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v1.3");
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ReconcileService.class)).addAppender(appender);
        ((Logger) LoggerFactory.getLogger(SavingsPlanAudit.class)).addAppender(appender);
        savingsRepo.now = NOW;
        service = new ReconcileService(gateway, positionRepo, decisionRepo, cooldownRepo, ruleVersions,
                mapper, telegram, executorNotifier, 3, 24, legRepo, new BigDecimal("0.50"),
                ConvictionProfile.defaults(), savingsRepo,
                new SavingsPlanAudit(decisionRepo, ruleVersions, mapper, telegram),
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
        book(SavingsFixtures.pos(1, "TECHA").build());
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(ReconcileService.class)).detachAndStopAllAppenders();
        ((Logger) LoggerFactory.getLogger(SavingsPlanAudit.class)).detachAndStopAllAppenders();
    }

    void book(ExecutorPosition p) {
        position = p;
        leg = SavingsFixtures.leg(p);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        when(positionRepo.findById(p.id())).thenReturn(p);
        when(legRepo.findOpenByPosition(p.id())).thenReturn(List.of(leg));
        when(legRepo.findByPosition(p.id())).thenReturn(List.of(leg));
    }

    InMemorySavingsPlanRepository.Row row(String status) {
        InMemorySavingsPlanRepository.Row r = savingsRepo.seed("2026-11", 1, "TECHA", status, "9", "112.20",
                "100.98", "10", "100", "65.00", Instant.parse("2026-11-02T23:00:00Z"));
        r.entryOrderId = "brk-9";
        r.childStopOrderId = "child-9";
        return r;
    }

    List<DecisionLog> decisions() {
        ArgumentCaptor<DecisionLog> c = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeast(0)).insert(c.capture());
        return c.getAllValues();
    }

    List<DecisionLog> trims() {
        return decisions().stream().filter(d -> "TRIM".equals(d.action())).toList();
    }

    List<String> lines(String prefix) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefix)).toList();
    }

    /** §6.1 skip list: no leg sync, no LEG_QTY_DESYNC, no updateMaintenance (QTY_SYNC,
     *  ENTRY_PRICE_SYNC), no UNCLAIMED_STOP_FILL for the child, no seeding. */
    @Test
    void anInFlightPositionSkipsTheNormalReconcileSteps() {
        var r = row(SavingsBuy.PLACED);
        gateway.seedPosition(new BrokerPosition("TECHA", "BUY", new BigDecimal("19"),
                new BigDecimal("105.77894736842105"), new BigDecimal("110"), null));
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        gateway.seedOrder(liveStop("child-9", "TECHA", "9", "89.76"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-2").survivors();

        assertThat(survivors).containsExactly(position);
        verify(positionRepo, never()).syncQty(anyLong(), any());
        verify(positionRepo, never()).syncEntryPrice(anyLong(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        verify(legRepo, never()).syncLegQty(anyLong(), any());
        verify(legRepo, never()).insertIfAbsent(any());
        assertThat(decisions()).extracting(DecisionLog::reasonCode)
                .doesNotContain("LEG_QTY_DESYNC", "LEG_QTY_SYNC", "QTY_SYNC", "ENTRY_PRICE_SYNC", "UNCLAIMED_STOP_FILL");
        assertThat(savingsRepo.findById(r.id).status()).isEqualTo(SavingsBuy.PLACED);
        assertThat(lines("savings-plan reconcile")).singleElement().satisfies(l -> assertThat(l)
                .contains("row=" + r.id).contains("status=PLACED").contains("action=skip-list")
                .contains("update-maintenance").contains("bp_qty=19"));
    }

    /** Matrix row 1: leg-1 stop filled, broker qty 0, the add never filled — HARD_STOP close through
     *  closePositionFromLegs, live parent cancelled, CLOSED_WITH_POSITION, carry deleted, no refund. */
    @Test
    void row1LegOneStopFilledAndTheAddNeverFilledClosesAsHardStop() {
        var r = row(SavingsBuy.PLACED);
        savingsRepo.carry.put(1L, new BigDecimal("91.18"));
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(liveParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-2").survivors();

        assertThat(survivors).isEmpty();
        assertThat(gateway.cancelledOrderIds).containsExactly("brk-9");
        verify(legRepo).closeLeg(10L, new BigDecimal("64.50"), "HARD_STOP", NOW);
        verify(positionRepo).close(eq(1L), eq(new BigDecimal("64.500000")), any(), eq("HARD_STOP"), eq("FILL"), any());
        assertThat(savingsRepo.findById(r.id).status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        assertThat(savingsRepo.carry).doesNotContainKey(1L);
        assertThat(trims()).isEmpty();
        assertThat(lines("savings-plan reconcile")).anySatisfy(l -> assertThat(l)
                .contains("action=matrix").contains("row=1").contains("leg1_fill=stop-1@64.5").contains("bp_qty=0"));
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("from=PLACED").contains("to=CLOSED_WITH_POSITION").contains("matrix_row=1"));
    }

    /** Row 1 with the add filled too: its buy is recorded and its shares leave as one window-stop TRIM
     *  at the child price, valued against the add's own entry. */
    @Test
    void row1WithTheAddFilledRecordsItsBuyAndAWindowStopTrimAtTheChildPrice() {
        var r = row(SavingsBuy.PLACED);
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedOrder(filledStop("child-9", "TECHA", "9", "89.70"));

        service.reconcile("c", "run-2");

        assertThat(trims()).singleElement().satisfies(t -> {
            assertThat(t.reasonCode()).isEqualTo("SAVINGS_WINDOW_STOP");
            assertThat(t.orderJson().path("qty_closed").decimalValue()).isEqualByComparingTo("9");
            assertThat(t.orderJson().path("price").decimalValue()).isEqualByComparingTo("89.70");
            assertThat(t.orderJson().path("entry_price").decimalValue()).isEqualByComparingTo("112.20");
            assertThat(t.orderJson().path("position_id").asLong()).isEqualTo(1L);
        });
        assertThat(savingsRepo.findById(r.id).fillQty()).isEqualByComparingTo("9");
        assertThat(savingsRepo.findById(r.id).fillPrice()).isEqualByComparingTo("112.20");
        verify(positionRepo).close(eq(1L), any(), any(), eq("HARD_STOP"), eq("FILL"), any());
    }

    /** Matrix row 2 (D8): leg-1 stop filled while the add holds shares — EMERGENCY_EXIT, ONE TRIM for the
     *  leg-1 shares valued against avg_before FROM THE ROW, leg 1 shrunk (not closed), book = add. */
    @Test
    void row2LegOneFilledWhileTheAddHoldsSharesIsD8() {
        book(SavingsFixtures.pos(1, "TECHA").entry("101").build());   // an intraday sync moved the book
        var r = row(SavingsBuy.PLACED);
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedPosition(new BrokerPosition("TECHA", "BUY", new BigDecimal("9"), new BigDecimal("112.2"),
                new BigDecimal("70"), null));

        service.reconcile("c", "run-2");

        SavingsBuy after = savingsRepo.findById(r.id);
        assertThat(after.status()).isEqualTo(SavingsBuy.EMERGENCY_EXIT);
        assertThat(after.fillQty()).isEqualByComparingTo("9");
        assertThat(after.fillPrice()).isEqualByComparingTo("112.20");
        assertThat(trims()).singleElement().satisfies(t -> {
            assertThat(t.reasonCode()).isEqualTo("SAVINGS_LEG1_STOP");
            assertThat(t.orderJson().path("qty_closed").decimalValue()).isEqualByComparingTo("10");
            assertThat(t.orderJson().path("price").decimalValue()).isEqualByComparingTo("64.50");
            assertThat(t.orderJson().path("entry_price").decimalValue()).isEqualByComparingTo("100");
        });
        verify(legRepo).setStopAndQty(10L, "child-9", new BigDecimal("9"));
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
        verify(positionRepo).bookSavingsQtyAndAvg(1L, new BigDecimal("9"), new BigDecimal("112.20"));
        verify(positionRepo).setStopOrderId(1L, "child-9");
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        assertThat(lines("savings-plan reconcile")).anySatisfy(l -> assertThat(l)
                .contains("action=matrix").contains("row=2").contains("bp_qty=9").contains("qty_before=10"));
    }

    /** R3: D8 with the 72 h fill lookback — the same leg-1 fill is seen again next pass, exactly one TRIM. */
    @Test
    void d8WithThe72hLookbackWritesExactlyOneTrim() {
        row(SavingsBuy.PLACED);
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedPosition(new BrokerPosition("TECHA", "BUY", new BigDecimal("9"), new BigDecimal("112.2"),
                new BigDecimal("70"), null));

        service.reconcile("c", "run-2");
        service.reconcile("c", "run-3");

        assertThat(trims()).hasSize(1);
    }

    /** R3/R4 Minor 7: the new stop fills before step 7 — book the step-7 state first, then close
     *  everything at the new stop's fill price; no window-stop TRIM. */
    @Test
    void theNewStopFillingBeforeStep7BooksTheConsolidatedStateThenCloses() {
        var r = row(SavingsBuy.CONSOLIDATING);
        r.newStopOrderId = "pstop-9";
        r.targetQty = new BigDecimal("19");
        r.targetStop = new BigDecimal("68.76");
        r.avgAfter = new BigDecimal("105.778947");
        ExecutorPosition booked = SavingsFixtures.pos(1, "TECHA").qty("19").entry("105.778947").stop("pstop-9").build();
        when(positionRepo.findById(1L)).thenReturn(booked);
        gateway.seedOrder(filledStop("pstop-9", "TECHA", "19", "68.70"));

        service.reconcile("c", "run-2");

        verify(positionRepo).bookSavingsQtyAndAvg(1L, new BigDecimal("19"), new BigDecimal("105.778947"));
        verify(positionRepo).setStopOrderId(1L, "pstop-9");
        verify(legRepo).setStopAndQty(10L, "pstop-9", new BigDecimal("19"));
        verify(positionRepo).close(eq(1L), eq(new BigDecimal("68.700000")), any(), eq("HARD_STOP"), eq("FILL"), any());
        assertThat(trims()).isEmpty();
        SavingsBuy after = savingsRepo.findById(r.id);
        assertThat(after.status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        assertThat(after.fillQty()).isEqualByComparingTo("9");
        assertThat(after.fillPrice()).isEqualByComparingTo("112.199999");
    }

    /** Row 1 after D8 (the consolidator's flatten answered POSITION_ALREADY_GONE, or the child stop
     *  filled): the book already IS the add (9 @ 112.20, stop child-9) and the buy + leg-1 TRIM are
     *  booked — the close at the child's fill covers the add shares; no second TRIM, the row's fill
     *  is kept even though the FILLED parent is still inside the 72 h lookback. */
    @Test
    void row1AfterD8ClosesTheAddSharesWithoutASecondTrim() {
        book(SavingsFixtures.pos(1, "TECHA").qty("9").entry("112.20").stop("child-9").build());
        var r = row(SavingsBuy.EMERGENCY_EXIT);
        r.fillQty = new BigDecimal("9");
        r.fillPrice = new BigDecimal("112.20");
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedOrder(filledStop("child-9", "TECHA", "9", "89.70"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run-3").survivors();

        assertThat(survivors).isEmpty();
        assertThat(trims()).isEmpty();
        verify(legRepo).closeLeg(10L, new BigDecimal("89.70"), "HARD_STOP", NOW);
        verify(positionRepo).close(eq(1L), eq(new BigDecimal("89.700000")), any(), eq("HARD_STOP"), eq("FILL"), any());
        SavingsBuy after = savingsRepo.findById(r.id);
        assertThat(after.status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        assertThat(after.fillQty()).isEqualByComparingTo("9");
        assertThat(after.fillPrice()).isEqualByComparingTo("112.20");
        assertThat(lines("savings-plan reconcile")).anySatisfy(l -> assertThat(l)
                .contains("action=matrix").contains("matrix_row=1").contains("leg1_fill=child-9@89.7")
                .contains("emergency=true"));
    }

    /** A second OPEN leg (synthetic id 11) next to leg 1. */
    void twoOpenLegs() {
        ExecutorPositionLeg second = new ExecutorPositionLeg(11L, position.id(), 2, "brk-x", "stop-x",
                new BigDecimal("1"), ExecutorPositionLeg.OPEN, null, null, null);
        when(legRepo.findOpenByPosition(position.id())).thenReturn(List.of(leg, second));
    }

    List<DecisionLog> transitionsTo(String reason) {
        return decisions().stream().filter(d -> reason.equals(d.reasonCode())).toList();
    }

    /** §8a never-silent: D8 with two OPEN legs cannot shrink "leg 1" — WARN SAVINGS_QTY_UNEXPLAINED,
     *  the transition carries open_legs, no leg is touched; the position is still booked to the add. */
    @Test
    void row2WithTwoOpenLegsEscalatesInsteadOfSilentlySkippingTheLegShrink() {
        twoOpenLegs();
        row(SavingsBuy.PLACED);
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedPosition(new BrokerPosition("TECHA", "BUY", new BigDecimal("9"), new BigDecimal("112.2"),
                new BigDecimal("70"), null));

        service.reconcile("c", "run-2");

        verify(legRepo, never()).setStopAndQty(anyLong(), any(), any());
        verify(positionRepo).bookSavingsQtyAndAvg(1L, new BigDecimal("9"), new BigDecimal("112.20"));
        assertThat(transitionsTo("SAVINGS_QTY_UNEXPLAINED")).singleElement().satisfies(d -> {
            assertThat(d.action()).isEqualTo("ESCALATE");
            assertThat(d.orderJson().path("detail").asText()).isEqualTo("open_legs:2");
        });
        assertThat(transitionsTo("EMERGENCY_EXIT")).singleElement().satisfies(d -> {
            assertThat(d.orderJson().path("open_legs").asInt()).isEqualTo(2);
            assertThat(d.orderJson().path("leg_id").isNull()).isTrue();
        });
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith("savings-plan escalation code=SAVINGS_QTY_UNEXPLAINED").contains("detail=open_legs:2"));
    }

    /** The D8 transition names the one leg it shrank. */
    @Test
    void row2TransitionNamesTheShrunkLeg() {
        row(SavingsBuy.PLACED);
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedPosition(new BrokerPosition("TECHA", "BUY", new BigDecimal("9"), new BigDecimal("112.2"),
                new BigDecimal("70"), null));

        service.reconcile("c", "run-2");

        assertThat(transitionsTo("EMERGENCY_EXIT")).singleElement().satisfies(d -> {
            assertThat(d.orderJson().path("open_legs").asInt()).isEqualTo(1);
            assertThat(d.orderJson().path("leg_id").asLong()).isEqualTo(10L);
        });
        assertThat(transitionsTo("SAVINGS_QTY_UNEXPLAINED")).isEmpty();
    }

    /** §8a never-silent: the new stop filled before step 7 with two OPEN legs — the step-7 leg update
     *  is skipped loudly (WARN open_legs:2); the close still runs. */
    @Test
    void newStopFilledWithTwoOpenLegsEscalatesTheSkippedLegBooking() {
        twoOpenLegs();
        var r = row(SavingsBuy.CONSOLIDATING);
        r.newStopOrderId = "pstop-9";
        r.targetQty = new BigDecimal("19");
        r.targetStop = new BigDecimal("68.76");
        r.avgAfter = new BigDecimal("105.778947");
        gateway.seedOrder(filledStop("pstop-9", "TECHA", "19", "68.70"));

        service.reconcile("c", "run-2");

        verify(legRepo, never()).setStopAndQty(anyLong(), any(), any());
        assertThat(transitionsTo("SAVINGS_QTY_UNEXPLAINED")).singleElement().satisfies(d ->
                assertThat(d.orderJson().path("detail").asText()).isEqualTo("open_legs:2"));
        assertThat(transitionsTo("CLOSED_WITH_POSITION")).singleElement().satisfies(d ->
                assertThat(d.orderJson().path("open_legs").asInt()).isEqualTo(2));
        verify(positionRepo).close(eq(1L), any(), any(), eq("HARD_STOP"), eq("FILL"), any());
    }

    @Test
    void withoutFillHistoryNoMatrixActionIsTakenThisPass() {
        var r = row(SavingsBuy.PLACED);
        gateway.filledOrdersUnavailable = true;

        List<ExecutorPosition> survivors = service.reconcile("c", "run-2").survivors();

        assertThat(survivors).containsExactly(position);
        assertThat(savingsRepo.findById(r.id).status()).isEqualTo(SavingsBuy.PLACED);
        assertThat(decisions()).extracting(DecisionLog::reasonCode).contains("FILL_HISTORY_UNAVAILABLE");
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void aVanishedPositionWithoutAnyFillTakesTheResolveExitPath() {
        var r = row(SavingsBuy.PLACED);

        service.reconcile("c", "run-2");

        verify(positionRepo).close(eq(1L), any(), any(), eq("RECONCILE_GONE"), eq("RECONCILE_GONE"), any());
        assertThat(savingsRepo.findById(r.id).status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
    }

    /** Final review I2: a row left CONSOLIDATING after a window stop (window_stop_qty = 3, its TRIM
     *  already written by the consolidator), then a full stop-out — matrix row 1 TRIMs only the add
     *  shares the window stop did not already cover (9 − 3), never the full parent fill again. */
    @Test
    void row1AfterAWindowStopTrimsOnlyTheAddSharesNotYetTrimmed() {
        var r = row(SavingsBuy.CONSOLIDATING);
        r.windowStopQty = new BigDecimal("3");
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedOrder(filledStop("child-9", "TECHA", "9", "89.70"));

        service.reconcile("c", "run-2");

        assertThat(trims()).singleElement().satisfies(t -> {
            assertThat(t.reasonCode()).isEqualTo("SAVINGS_WINDOW_STOP");
            assertThat(t.orderJson().path("qty_closed").decimalValue()).isEqualByComparingTo("6");
        });
        assertThat(savingsRepo.findById(r.id).status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        assertThat(savingsRepo.findById(r.id).fillQty()).as("gross add fill").isEqualByComparingTo("9");
    }

    /** Final review I2: when window_stop_qty already covers every add share, row 1 writes no TRIM. */
    @Test
    void row1WritesNoTrimWhenTheWindowStopCoveredEveryAddShare() {
        var r = row(SavingsBuy.CONSOLIDATING);
        r.windowStopQty = new BigDecimal("9");
        gateway.seedOrder(filledStop("stop-1", "TECHA", "10", "64.50"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedOrder(filledStop("child-9", "TECHA", "9", "89.70"));

        service.reconcile("c", "run-2");

        assertThat(trims()).isEmpty();
        assertThat(savingsRepo.findById(r.id).status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        verify(positionRepo).close(eq(1L), any(), any(), eq("HARD_STOP"), eq("FILL"), any());
    }

    /** Final review I2: the new stop fills before step 7 after a window stop — target_qty (16) is net of
     *  the 3 window-stopped shares; the row's fill_qty is the gross add fill (16 − 10 + 3 = 9) and the
     *  fill price is derived over the gross fill. */
    @Test
    void theNewStopFillingAfterAWindowStopBooksTheGrossAddFill() {
        var r = row(SavingsBuy.CONSOLIDATING);
        r.windowStopQty = new BigDecimal("3");
        r.newStopOrderId = "pstop-9";
        r.targetQty = new BigDecimal("16");
        r.targetStop = new BigDecimal("68.76");
        r.avgAfter = new BigDecimal("105.778947");
        ExecutorPosition booked = SavingsFixtures.pos(1, "TECHA").qty("16").entry("105.778947").stop("pstop-9").build();
        when(positionRepo.findById(1L)).thenReturn(booked);
        gateway.seedOrder(filledStop("pstop-9", "TECHA", "16", "68.70"));

        service.reconcile("c", "run-2");

        SavingsBuy after = savingsRepo.findById(r.id);
        assertThat(after.status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        assertThat(after.fillQty()).isEqualByComparingTo("9");
        assertThat(after.fillPrice()).isEqualByComparingTo("112.199999");
        assertThat(trims()).isEmpty();
    }
}
