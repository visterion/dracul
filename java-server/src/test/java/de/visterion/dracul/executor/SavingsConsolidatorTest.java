package de.visterion.dracul.executor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.executor.broker.ModifyResult;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static de.visterion.dracul.executor.SavingsFixtures.filledParent;
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

/** Spec 2026-10-06 §5, §12 ConsolidationTest. Standard book: TECHA, 10 shares at 100, leg-1 stop
 *  "stop-1" 10 @ 65.00; the November add (row PLACED, 9 @ 112.20, child "child-9" 9 @ 89.76) was
 *  placed Mon 2026-11-02 23:00 UTC; the consolidating pass is Tue 2026-11-03 23:00 UTC. */
class SavingsConsolidatorTest {

    static final Instant ADD_AT = Instant.parse("2026-11-02T23:00:00Z");
    static final Instant NEXT_DAY = Instant.parse("2026-11-03T23:00:00Z");

    /** Records the order of broker writes — §5.2 step 6 has two legal orders. */
    static class SequencedGateway extends FakeExecutionGateway {
        final List<String> ops = new ArrayList<>();
        /** Cancels that TAKE EFFECT at the broker and then still throw (timeout / ORDER_NOT_FOUND). */
        final java.util.Map<String, RuntimeException> effectiveCancelFailures = new java.util.HashMap<>();

        @Override
        public String placeProtectiveStop(String c, String s, BigDecimal q, BigDecimal p) {
            ops.add("place:" + q.stripTrailingZeros().toPlainString() + "@" + p.stripTrailingZeros().toPlainString());
            return super.placeProtectiveStop(c, s, q, p);
        }

        @Override
        public void cancelOrder(String c, String id) {
            ops.add("cancel:" + id);
            super.cancelOrder(c, id);
            RuntimeException after = effectiveCancelFailures.get(id);
            if (after != null) throw after;
        }

        @Override
        public ModifyResult modifyBracket(String c, String o, String s, BigDecimal st, BigDecimal t,
                String so, String to) {
            ops.add("modify:" + so + "@" + st.stripTrailingZeros().toPlainString());
            return super.modifyBracket(c, o, s, st, t, so, to);
        }
    }

    final SequencedGateway gateway = new SequencedGateway();
    final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    boolean failNewStopCas;
    final InMemorySavingsPlanRepository savingsRepo = new InMemorySavingsPlanRepository() {
        @Override
        public boolean setNewStopOrderId(long id, String expected, String newStopOrderId) {
            return !failNewStopCas && super.setNewStopOrderId(id, expected, newStopOrderId);
        }
    };
    final ExecutorIndicators indicators = mock(ExecutorIndicators.class);
    final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    final TelegramNotifier telegram = mock(TelegramNotifier.class);
    final HardTriggerService hardTrigger = mock(HardTriggerService.class);
    ListAppender<ILoggingEvent> appender;
    SavingsConsolidator consolidator;
    ExecutorPosition position;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v1.3");
        gateway.cancelRemovesOrder = true;
        savingsRepo.now = NEXT_DAY;
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(SavingsPlanAudit.class)).addAppender(appender);
        ((Logger) LoggerFactory.getLogger(SavingsConsolidator.class)).addAppender(appender);
        consolidator = consolidator(false, true);
        position = SavingsFixtures.pos(1, "TECHA").build();
        book(position);
        when(indicators.levels("TECHA", 22, 20)).thenReturn(SavingsFixtures.levels("110"));
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(SavingsPlanAudit.class)).detachAndStopAllAppenders();
        ((Logger) LoggerFactory.getLogger(SavingsConsolidator.class)).detachAndStopAllAppenders();
    }

    SavingsConsolidator consolidator(boolean placeFirst, boolean enabled) {
        SavingsPlanSettings s = new SavingsPlanSettings(enabled, new BigDecimal("0.02"),
                new BigDecimal("0.08"), new BigDecimal("0.50"), new BigDecimal("0.02"), 3,
                LocalTime.of(21, 15), "gtc", placeFirst);
        return new SavingsConsolidator(gateway, positionRepo, legRepo, savingsRepo, indicators,
                ConvictionProfile.defaults(), s,
                new SavingsPlanAudit(decisionRepo, ruleVersions, new ObjectMapper(), telegram),
                TransactionOperations.withoutTransaction(), hardTrigger, 22, 20);
    }

    void book(ExecutorPosition p) {
        when(positionRepo.findById(p.id())).thenReturn(p);
        when(legRepo.findOpenByPosition(p.id())).thenReturn(List.of(SavingsFixtures.leg(p)));
    }

    InMemorySavingsPlanRepository.Row placedRow() {
        InMemorySavingsPlanRepository.Row r = savingsRepo.seed("2026-11", 1, "TECHA", SavingsBuy.PLACED,
                "9", "112.20", "100.98", "10", "100", "65.00", ADD_AT);
        r.entryOrderId = "brk-9";
        r.childStopOrderId = "child-9";
        return r;
    }

    void legStops() {
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        gateway.seedOrder(liveStop("child-9", "TECHA", "9", "89.76"));
    }

    void broker(String qty, String avg) {
        gateway.seedPosition(new BrokerPosition("TECHA", "BUY", new BigDecimal(qty), new BigDecimal(avg),
                new BigDecimal("110"), null));
    }

    List<String> lines(String prefix) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefix)).toList();
    }

    List<String> reasonCodes() {
        ArgumentCaptor<DecisionLog> c = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeast(0)).insert(c.capture());
        return c.getAllValues().stream().map(DecisionLog::reasonCode).toList();
    }

    SavingsBuy row(long id) {
        return savingsRepo.findById(id);
    }

    @Test
    void aFullFillCancelsEveryLiveStopThenPlacesOneStopAtTheAverageTimes065() {
        var r = placedRow();
        legStops();
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:child-9", "place:19@68.76");
        String newStop = "pstop-1";                     // the fake's first protective-stop id
        verify(positionRepo).bookSavingsQtyAndAvg(1L, new BigDecimal("19"), new BigDecimal("105.778947"));
        verify(positionRepo).bookSavingsStop(1L, newStop, new BigDecimal("68.76"), new BigDecimal("68.76"), false);
        verify(legRepo).setStopAndQty(10L, newStop, new BigDecimal("19"));
        SavingsBuy done = row(r.id);
        assertThat(done.status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(done.fillQty()).isEqualByComparingTo("9");
        assertThat(done.fillPrice()).isEqualByComparingTo("112.199999");
        assertThat(done.avgAfter()).isEqualByComparingTo("105.778947");
        assertThat(done.stopAfter()).isEqualByComparingTo("68.76");
        assertThat(done.newStopOrderId()).isEqualTo(newStop);
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("0");

        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("from=PLACED").contains("to=CONSOLIDATING").contains("bp_qty=19")
                .contains("bp_avg=105.778947").contains("target_stop=68.76").contains("qty_before=10"));
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("from=CONSOLIDATING").contains("to=CONSOLIDATED").contains("fill_qty=9")
                .contains("new_stop_order_id=" + newStop).contains("stop_moved_down=false"));
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("stage=consolidate").contains("outcome=acted").contains("consolidated=1"));
        assertThat(reasonCodes()).contains("CONSOLIDATING", "NEW_STOP_PERSISTED", "CONSOLIDATED");
    }

    @Test
    void placeFirstPlacesTheNewStopBeforeCancellingTheOldOnes() {
        consolidator = consolidator(true, true);
        placedRow();
        legStops();
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("place:19@68.76", "cancel:stop-1", "cancel:child-9");
    }

    @Test
    void aPartialFillTakesItsQtyFromTheBrokerPositionAndRefundsTheRest() {
        var r = placedRow();
        legStops();
        gateway.seedOrder(new BrokerOrder("brk-9", "sp-1-202611", "TECHA", OrderRole.OTHER,
                OrderStatus.PARTIALLY_FILLED, new BigDecimal("9"), new BigDecimal("4"),
                new BigDecimal("112.20"), null, "buy", "limit", "partialfill", "open",
                new BigDecimal("112.20"), null, null));
        broker("14", "103.48571428571");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:brk-9", "cancel:stop-1", "cancel:child-9", "place:14@67.27");
        assertThat(row(r.id).fillQty()).isEqualByComparingTo("4");
        assertThat(row(r.id).fillPrice()).isEqualByComparingTo("112.199999");
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("504.90");   // (9 − 4) × 100.98
    }

    @Test
    void noFillExpiresRefundsTheCarryCancelsTheChildAndLeavesLegOne() {
        var r = placedRow();
        legStops();
        broker("10", "100");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:child-9");
        assertThat(gateway.protectiveStops).isEmpty();
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.EXPIRED);
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("908.82");     // 9 × 100.98
        verify(positionRepo, never()).bookSavingsStop(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("from=PLACED").contains("to=EXPIRED").contains("carry_refund_eur=908.82"));
    }

    @Test
    void aWorkingGtcParentIsCancelledBeforeItExpires() {
        var r = placedRow();
        legStops();
        gateway.seedOrder(liveParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        broker("10", "100");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:brk-9", "cancel:child-9");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.EXPIRED);
    }

    @Test
    void aNullChildIdIsSweptBySymbol() {
        var r = placedRow();
        r.childStopOrderId = null;
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        gateway.seedOrder(liveStop("x-77", "TECHA", "9", "89.76"));
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:x-77", "place:19@68.76");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
    }

    /** §12: two consecutive months on one position; the second add is below the old average, so the
     *  stop moves DOWN (audited bypass of StopRatchetGuard; initial_stop untouched). */
    @Test
    void theSecondMonthConsolidatesTheConsolidatedPositionAndTheStopMovesDown() {
        position = SavingsFixtures.pos(1, "TECHA").qty("19").entry("105.778947").activeStop("68.76").stop("cons-1").build();
        book(position);
        var r = savingsRepo.seed("2026-12", 1, "TECHA", SavingsBuy.PLACED, "8", "100.00", "90.00", "19",
                "105.778947", "68.76", Instant.parse("2026-12-01T23:00:00Z"));
        r.entryOrderId = "brk-12";
        r.childStopOrderId = "child-12";
        gateway.seedOrder(liveStop("cons-1", "TECHA", "19", "68.76"));
        gateway.seedOrder(liveStop("child-12", "TECHA", "8", "80.00"));
        broker("27", "104.06666666667");
        Instant dec2 = Instant.parse("2026-12-02T23:00:00Z");
        savingsRepo.now = dec2;

        consolidator.consolidateStage("c", "run-3", "pass-3", dec2);

        assertThat(gateway.ops).containsExactly("cancel:cons-1", "cancel:child-12", "place:27@67.65");
        assertThat(row(r.id).fillPrice()).isEqualByComparingTo("100.000002");
        assertThat(row(r.id).stopAfter()).isEqualByComparingTo("67.65");
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("to=CONSOLIDATED").contains("stop_before=68.76").contains("target_stop=67.65")
                .contains("stop_moved_down=true"));
    }

    @Test
    void afterAHalfSaleTheTargetIsNeverBelowTheTrail() {
        position = SavingsFixtures.pos(1, "TECHA").trimCount(1).activeStop("80.00").build();
        book(position);
        placedRow();
        legStops();
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).contains("place:19@80");
    }

    @Test
    void aBandRejectPlacesAtTheBandThenModifiesToTheTarget() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerRejectedException("band", "PRICE_OUT_OF_BAND", List.of()));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:child-9", "place:19@68.76",
                "place:19@88", "modify:pstop-1@68.76");
        verify(positionRepo).bookSavingsStop(1L, "pstop-1", new BigDecimal("68.76"), new BigDecimal("68.76"), false);
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
    }

    @Test
    void aRejectedModifyLeavesTheNarrowBandStopAndEscalates() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerRejectedException("band", "PRICE_OUT_OF_BAND", List.of()));
        gateway.modifyFailures = 1;
        gateway.modifyRejectCode = "PRICE_OUT_OF_BAND";

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        verify(positionRepo).bookSavingsStop(eq(1L), eq("pstop-1"), eq(new BigDecimal("68.76")),
                org.mockito.ArgumentMatchers.argThat(b -> b.compareTo(new BigDecimal("88")) == 0), eq(true));
        assertThat(reasonCodes()).contains("CONSOLIDATE_NARROW");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
    }

    @Test
    void aFailedCancelReplacesTheAlreadyCancelledStopsAndPlacesNothingNew() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.failCancelForOrderId = "child-9";

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:child-9", "place:10@65");
        verify(positionRepo).setStopOrderId(1L, "pstop-1");
        verify(legRepo).repointLegStop(10L, "pstop-1");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATING);
        assertThat(reasonCodes()).contains("CONSOLIDATE_CANCEL_FAILED");
    }

    @Test
    void bothPlacesRejectedReplacesTheOldStops() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerRejectedException("no", "X", List.of()));
        gateway.protectiveStopFailures.add(new BrokerRejectedException("no", "X", List.of()));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:child-9", "place:19@68.76",
                "place:19@88", "place:10@65", "place:9@89.76");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATING);
        assertThat(reasonCodes()).contains("CONSOLIDATE_PLACE_FAILED");
    }

    @Test
    void anIndeterminatePlaceAfterTheCancelsIsUnprotectedAndCritical() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerUnavailableException("5xx"));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.UNPROTECTED);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).startsWith("savings-plan escalation")
                    .contains("code=CONSOLIDATE_UNPROTECTED").contains("severity=CRITICAL");
        });
    }

    @Test
    void theSessionGateKeepsASameEveningPassAwayAndSaysSo() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-op", "pass-op", Instant.parse("2026-11-02T23:05:00Z"));

        assertThat(gateway.ops).isEmpty();
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.PLACED);
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("outcome=did-nothing").contains("why=session-gate-not-passed"));
    }

    @Test
    void aStageWithoutRowsSaysWhy() {
        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("stage=consolidate").contains("outcome=did-nothing").contains("why=no-rows"));
    }

    @Test
    void enabledFalseStillConsolidatesAPlacedRow() {
        consolidator = consolidator(false, false);
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
    }

    @Test
    void anIndeterminatePlacingRowIsAdoptedViaTheHistoryAndConsolidated() {
        var r = savingsRepo.seed("2026-11", 1, "TECHA", SavingsBuy.PLACING, "9", "112.20", "100.98",
                "10", "100", "65.00", ADD_AT);
        legStops();
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(row(r.id).entryOrderId()).isEqualTo("brk-9");
        assertThat(reasonCodes()).contains("ADOPTED").doesNotContain("REJECTED");
    }

    /** R3 M4: a partial-then-expired parent is NOT in the FILLED-only history; the broker qty adopts it. */
    @Test
    void aPartialThenExpiredParentIsAdoptedViaTheQtyTest() {
        var r = savingsRepo.seed("2026-11", 1, "TECHA", SavingsBuy.PLACING, "9", "112.20", "100.98",
                "10", "100", "65.00", ADD_AT);
        legStops();
        broker("13", "102.82307692308");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(row(r.id).fillQty()).isEqualByComparingTo("3");
    }

    @Test
    void aPlacingRowTheBrokerNeverSawIsRejectedOnlyAfterTheSessionGate() {
        var r = savingsRepo.seed("2026-11", 1, "TECHA", SavingsBuy.PLACING, "9", "112.20", "100.98",
                "10", "100", "65.00", ADD_AT);
        legStops();
        broker("10", "100");

        consolidator.consolidateStage("c", "run-1b", "pass-1b", Instant.parse("2026-11-02T23:05:00Z"));
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.PLACING);

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.REJECTED);
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("908.82");
    }
    /** Review I1: a cancel refused as ORDER_NOT_FOUND means the stop is GONE — the fresh order read
     *  finds it no longer live and it is re-placed at its old qty/price, never left uncovered. */
    @Test
    void aRefusedOldStopCancelWhoseStopIsGoneIsRestored() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.effectiveCancelFailures.put("stop-1",
                new BrokerRejectedException("gone", "ORDER_NOT_FOUND", List.of()));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "place:10@65");
        verify(positionRepo).setStopOrderId(1L, "pstop-1");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATING);
        assertThat(lines("savings-plan row failed")).isEmpty();
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=CONSOLIDATE_CANCEL_FAILED").contains("restored_old_stops=stop-1")
                .contains("coverage=19").contains("covered=true"));
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("outcome=acted").contains("outcomes={escalated:1}"));
    }

    /** Review I1: stop-1 cancels, the child-9 cancel times out but took effect — BOTH are re-placed. */
    @Test
    void aTimedOutCancelThatTookEffectIsRestoredToo() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.effectiveCancelFailures.put("child-9", new BrokerUnavailableException("timeout"));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:child-9", "place:10@65", "place:9@89.76");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATING);
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=CONSOLIDATE_CANCEL_FAILED").contains("restored_old_stops=stop-1,child-9")
                .contains("covered=true"));
    }

    /** Review I1: after the recovery the live stops cover less than bp.qty (the child was never
     *  live) — the row goes UNPROTECTED (CRITICAL) instead of a trigger-excluded CONSOLIDATING. */
    @Test
    void aFailedCancelWithShortCoverageIsUnprotected() {
        var r = placedRow();
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        broker("19", "105.77894736842105");
        gateway.failCancelForOrderId = "stop-1";

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.UNPROTECTED);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("code=CONSOLIDATE_UNPROTECTED")
                    .contains("cover only 10 of bp_qty 19");
        });
    }

    /** Review I2: no live old stop at all + target and band rejected ⇒ nothing covers the shares. */
    @Test
    void noOldStopsAndBothPlacesRejectedIsUnprotectedNotARestoreClaim() {
        var r = placedRow();
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerRejectedException("no", "X", List.of()));
        gateway.protectiveStopFailures.add(new BrokerRejectedException("no", "X", List.of()));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("place:19@68.76", "place:19@88");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.UNPROTECTED);
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=CONSOLIDATE_PLACE_FAILED").contains("severity=CRITICAL").contains("covered=false")
                .doesNotContain("were re-placed"));
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=CONSOLIDATE_UNPROTECTED").contains("severity=CRITICAL"));
    }

    /** Review I2, place-first: no new stop and the old stops do not cover bp.qty ⇒ UNPROTECTED. */
    @Test
    void placeFirstWithoutCoveringOldStopsIsUnprotected() {
        consolidator = consolidator(true, true);
        var r = placedRow();
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerUnavailableException("5xx"));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("place:19@68.76");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.UNPROTECTED);
    }

    @Test
    void placeFirstWithCoveringOldStopsStaysConsolidatingAndWarns() {
        consolidator = consolidator(true, true);
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerUnavailableException("5xx"));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATING);
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=CONSOLIDATE_PLACE_FAILED").contains("covered=true"));
    }

    /** Review I3: a step-7 booking with != 1 OPEN leg is never silent. */
    @Test
    void aBookingWithoutASingleOpenLegEscalates() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        ExecutorPositionLeg leg = SavingsFixtures.leg(position);
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(leg, leg));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        verify(legRepo, never()).setStopAndQty(anyLong(), any(), any());
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("to=CONSOLIDATED").contains("open_legs=2").contains("leg_id=null"));
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=SAVINGS_QTY_UNEXPLAINED").contains("detail=open_legs:2"));
    }

    @Test
    void theConsolidatedLineNamesTheLeg() {
        placedRow();
        legStops();
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("to=CONSOLIDATED").contains("open_legs=1").contains("leg_id=10"));
        assertThat(lines("savings-plan escalation")).isEmpty();
    }

    @Test
    void aRepointWithoutASingleOpenLegEscalates() {
        placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.failCancelForOrderId = "child-9";
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of());

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        verify(legRepo, never()).repointLegStop(anyLong(), any());
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("open_legs=0").contains("new_stop_order_id=pstop-1"));
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=SAVINGS_QTY_UNEXPLAINED").contains("detail=open_legs:0"));
    }

    /** Review Minor 1: other-connection and missing-position rows are counted, the latter at WARN. */
    @Test
    void otherConnectionAndMissingPositionRowsAreCounted() {
        savingsRepo.seed("2026-11", 2, "TECHB", SavingsBuy.PLACED, "1", "50", "45", "5", "50", "32.50", ADD_AT);
        savingsRepo.seed("2026-11", 3, "TECHC", SavingsBuy.PLACED, "1", "50", "45", "5", "50", "32.50", ADD_AT);
        ExecutorPosition other = SavingsFixtures.pos(2, "TECHB").build();
        when(positionRepo.findById(2L)).thenReturn(other);

        consolidator.consolidateStage("other-conn", "run-2", "pass-2", NEXT_DAY);

        assertThat(lines("savings-plan row failed")).singleElement().satisfies(l -> assertThat(l)
                .contains("position=3").contains("error=position-missing"));
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("error=position-missing");
        });
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("outcome=did-nothing").contains("why=position-missing")
                .contains("outcomes={other-connection:1,position-missing:1}"));
    }

    /** Review Minor 3: a lost CAS on new_stop_order_id is a WARN, never silent. */
    @Test
    void aLostNewStopCasIsEscalated() {
        placedRow();
        legStops();
        broker("19", "105.77894736842105");
        failNewStopCas = true;

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=NEW_STOP_NOT_PERSISTED").contains("new_stop_order_id=pstop-1")
                .contains("persisted=false"));
    }

    /** Review Minor 4: rows that only wait for adoption say so. */
    @Test
    void aPassThatOnlyWaitsForAdoptionSaysSo() {
        savingsRepo.seed("2026-11", 1, "TECHA", SavingsBuy.PLACING, "9", "112.20", "100.98",
                "10", "100", "65.00", ADD_AT);
        legStops();
        broker("10", "100");

        consolidator.consolidateStage("c", "run-1b", "pass-1b", Instant.parse("2026-11-02T23:05:00Z"));

        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("outcome=did-nothing").contains("why=waiting-adoption"));
    }

    /** §5.3: re-placing an already-cancelled old stop is refused → UNPROTECTED, CRITICAL. */
    @Test
    void aRefusedRestoreLeavesTheRowUnprotectedAndCritical() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.failCancelForOrderId = "child-9";
        gateway.protectiveStopFailures.add(new BrokerRejectedException("no", "X", List.of()));

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:child-9", "place:10@65");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.UNPROTECTED);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("code=CONSOLIDATE_UNPROTECTED");
        });
    }

    /** No verdict on the band→target modify: book the band (tighter, protective) as narrow. */
    @Test
    void anIndeterminateModifyBooksTheBandAsNarrowAndEscalates() {
        var r = placedRow();
        legStops();
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerRejectedException("band", "PRICE_OUT_OF_BAND", List.of()));
        gateway.modifyFailures = 1;

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        verify(positionRepo).bookSavingsStop(eq(1L), eq("pstop-1"), eq(new BigDecimal("68.76")),
                org.mockito.ArgumentMatchers.argThat(b -> b.compareTo(new BigDecimal("88")) == 0), eq(true));
        assertThat(lines("savings-plan escalation")).anySatisfy(l -> assertThat(l)
                .contains("code=CONSOLIDATE_NARROW").contains("reject_code=indeterminate"));
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
    }

    @Test
    void brokerCallsAreTracedBeforeAndAfter() {
        placedRow();
        legStops();
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l)
                .contains("op=placeProtectiveStop").contains("phase=intent").contains("qty=19").contains("stop=68.76"));
        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l)
                .contains("op=placeProtectiveStop").contains("phase=result").contains("result=accepted")
                .contains("order=pstop-1"));
        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l)
                .contains("op=cancelOrder").contains("order=child-9").contains("phase=intent"));
    }

    InMemorySavingsPlanRepository.Row consolidatingRow(String newStop) {
        InMemorySavingsPlanRepository.Row r = placedRow();
        r.status = SavingsBuy.CONSOLIDATING;
        r.newStopOrderId = newStop;
        r.targetQty = new BigDecimal("19");
        r.targetStop = new BigDecimal("68.76");
        return r;
    }

    /** §5.3 / R3: a crash between step 6 and 7 after a place-first place — the next pass keeps exactly
     *  one stop (new_stop_order_id), cancels the other and books step 7. */
    @Test
    void resumeAfterACrashKeepsTheNewStopCancelsTheRestAndBooks() {
        var r = consolidatingRow("pstop-9");
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        gateway.seedOrder(liveStop("pstop-9", "TECHA", "19", "68.76"));
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1");
        verify(positionRepo).bookSavingsStop(1L, "pstop-9", new BigDecimal("68.76"), new BigDecimal("68.76"), false);
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("from=CONSOLIDATING").contains("to=CONSOLIDATING").contains("keep=pstop-9"));
    }

    @Test
    void resumeAdoptsALiveStopMatchingQtyAndTargetWhenTheIdWasLost() {
        var r = consolidatingRow(null);
        gateway.seedOrder(liveStop("x-5", "TECHA", "19", "68.76"));
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY);

        assertThat(gateway.ops).isEmpty();
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(row(r.id).newStopOrderId()).isNull();
        verify(positionRepo).bookSavingsStop(1L, "x-5", new BigDecimal("68.76"), new BigDecimal("68.76"), false);
    }

    @Test
    void resumeWithoutAnyLiveStopIsUnprotectedThenReplacesTheTarget() {
        var r = consolidatingRow(null);
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("place:19@68.76");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(reasonCodes()).contains("UNPROTECTED", "CONSOLIDATE_UNPROTECTED", "CONSOLIDATED");
    }

    @Test
    void anUnprotectedRowEscalatesEveryPassWhileUncovered() {
        var r = consolidatingRow(null);
        r.status = SavingsBuy.UNPROTECTED;
        broker("19", "105.77894736842105");
        gateway.protectiveStopFailures.add(new BrokerUnavailableException("5xx"));
        gateway.protectiveStopFailures.add(new BrokerUnavailableException("5xx"));

        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY);
        consolidator.consolidateStage("c", "run-4", "pass-4", NEXT_DAY.plusSeconds(3600));

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.UNPROTECTED);
        assertThat(reasonCodes().stream().filter("CONSOLIDATE_UNPROTECTED"::equals).count()).isGreaterThanOrEqualTo(2);
    }

    /** §12: the broker qty changed before the resume — the target is recomputed and a stop sized to the
     *  old qty is NOT kept (no QTY_EXCEEDS_POSITION loop). */
    @Test
    void aChangedBrokerQtyRecomputesTheTargetOnResume() {
        var r = consolidatingRow("pstop-9");
        gateway.seedOrder(liveStop("pstop-9", "TECHA", "19", "68.76"));
        broker("15", "104");

        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:pstop-9", "place:15@67.6");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(row(r.id).stopAfter()).isEqualByComparingTo("67.60");
    }

    @Test
    void aParentStillWorkingAfterItsCancelInTwoClosedPassesIsStuck() {
        var r = placedRow();
        legStops();
        gateway.seedOrder(liveParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.failCancelForOrderId = "brk-9";
        broker("10", "100");
        when(decisionRepo.countByReasonCodeForSavingsBuy("CONSOLIDATE_CANCEL_FAILED", r.id)).thenReturn(0, 1);
        when(decisionRepo.countByReasonCodeForSavingsBuy("CONSOLIDATE_STUCK", r.id)).thenReturn(0);

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);
        assertThat(reasonCodes()).contains("CONSOLIDATE_CANCEL_FAILED").doesNotContain("CONSOLIDATE_STUCK");

        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY.plusSeconds(86_400));
        assertThat(reasonCodes()).contains("CONSOLIDATE_STUCK");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.PLACED);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("code=CONSOLIDATE_STUCK");
        });
    }

    /** Merge with Task 7's coverage rule: live stops that cover less than bp.qty are "uncovered" on
     *  resume too — UNPROTECTED + CRITICAL, then the target replaces them. */
    @Test
    void resumeWithLiveStopsBelowTheBrokerQtyIsUnprotectedThenReplacesTheTarget() {
        var r = consolidatingRow(null);
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        broker("19", "105.77894736842105");

        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:stop-1", "place:19@68.76");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(reasonCodes()).contains("RESUME", "UNPROTECTED", "CONSOLIDATE_UNPROTECTED", "CONSOLIDATED");
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith("savings-plan escalation").contains("code=CONSOLIDATE_UNPROTECTED")
                .contains("cover_only_10_of_bp_qty_19"));
    }

    /** §5.2 step 3a: the child stop filled inside the add's session and took every add share —
     *  one TRIM (SAVINGS_WINDOW_STOP, entry_price = add fill), row WINDOW_STOPPED, no stop work. */
    @Test
    void aChildStopThatTookEveryAddShareIsAWindowStopWithOneTrim() {
        var r = placedRow();
        gateway.seedOrder(liveStop("stop-1", "TECHA", "10", "65.00"));
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedOrder(SavingsFixtures.filledStop("child-9", "TECHA", "9", "90.00"));
        broker("10", "100");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).isEmpty();
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.WINDOW_STOPPED);
        assertThat(row(r.id).windowStopQty()).isEqualByComparingTo("9");
        assertThat(row(r.id).fillQty()).isEqualByComparingTo("9");
        ArgumentCaptor<DecisionLog> c = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeast(1)).insert(c.capture());
        List<DecisionLog> trims = c.getAllValues().stream().filter(d -> "TRIM".equals(d.action())).toList();
        assertThat(trims).singleElement().satisfies(t -> {
            assertThat(t.reasonCode()).isEqualTo("SAVINGS_WINDOW_STOP");
            assertThat(t.orderJson().path("qty_closed").decimalValue()).isEqualByComparingTo("9");
            assertThat(t.orderJson().path("price").decimalValue()).isEqualByComparingTo("90.00");
            assertThat(t.orderJson().path("entry_price").decimalValue()).isEqualByComparingTo("112.20");
            assertThat(t.orderJson().path("position_id").asLong()).isEqualTo(1L);
        });
        assertThat(savingsRepo.carryOf(1)).as("no refund on a window stop").isEqualByComparingTo("0");
    }

    @Test
    void anUnexplainedShortfallEscalatesThenConsolidatesOnTheBrokerQty() {
        var r = placedRow();
        legStops();
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        broker("16", "104");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(reasonCodes()).contains("SAVINGS_QTY_UNEXPLAINED");
        assertThat(gateway.ops).containsExactly("cancel:stop-1", "cancel:child-9", "place:16@67.6");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(row(r.id).fillQty()).isEqualByComparingTo("9");
        assertThat(row(r.id).fillPrice()).isEqualByComparingTo("112.20");
    }

    @Test
    void theWindowStopMarkerPreventsASecondTrim() {
        var r = placedRow();
        r.windowStopQty = new BigDecimal("3");
        legStops();
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        gateway.seedOrder(SavingsFixtures.filledStop("x-3", "TECHA", "3", "95.00"));
        broker("16", "104");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        ArgumentCaptor<DecisionLog> c = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeast(1)).insert(c.capture());
        assertThat(c.getAllValues()).noneMatch(d -> "TRIM".equals(d.action()));
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
    }

    /** §5.1 global rule (R3 M5, R4 Minor 1): pending exit — cancel the parent, book the filled add
     *  shares so the flatten sells what the book holds, terminal, no refund; ignores the session gate. */
    @Test
    void theGlobalRuleOnAPendingExitBooksFilledSharesAndEndsTheRow() {
        position = SavingsFixtures.pos(1, "TECHA").pendingExit("HARD_STOP").build();
        book(position);
        var r = placedRow();
        legStops();
        gateway.seedOrder(filledParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        broker("19", "105.77894736842105");
        savingsRepo.carry.put(1L, new BigDecimal("91.18"));

        consolidator.consolidateStage("c", "run-op", "pass-op", Instant.parse("2026-11-02T23:05:00Z"));

        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        assertThat(row(r.id).fillQty()).isEqualByComparingTo("9");
        verify(positionRepo).bookSavingsQtyAndAvg(1L, new BigDecimal("19"), new BigDecimal("105.778947"));
        verify(legRepo).syncLegQty(10L, new BigDecimal("19"));
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("91.18");
        assertThat(gateway.protectiveStops).isEmpty();
    }

    /** §6.2: a catastrophe-flagged position with an add in flight — the parent is cancelled and the row
     *  ends BEFORE the trigger filter, so the catastrophe flatten runs in the same pass. */
    @Test
    void aCatastropheFlaggedPositionEndsItsRowAndCancelsTheParent() {
        position = SavingsFixtures.pos(1, "TECHA").catastrophe("synthetic fraud").build();
        book(position);
        var r = placedRow();
        legStops();
        gateway.seedOrder(liveParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        broker("10", "100");

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        assertThat(gateway.ops).containsExactly("cancel:brk-9");
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        verify(positionRepo, never()).bookSavingsQtyAndAvg(anyLong(), any(), any());
    }

    InMemorySavingsPlanRepository.Row emergencyRow() {
        InMemorySavingsPlanRepository.Row r = placedRow();
        r.status = SavingsBuy.EMERGENCY_EXIT;
        r.fillQty = new BigDecimal("9");
        r.fillPrice = new BigDecimal("112.20");
        return r;
    }

    /** §6.1 row 2 (D8) next closed pass: cancel ONLY the BUY parent, then the hard-exit path; terminal in
     *  the same step; never re-flattened afterwards. */
    @Test
    void theEmergencyStepCancelsOnlyTheBuyParentFlattensAndNeverRepeats() {
        position = SavingsFixtures.pos(1, "TECHA").qty("9").entry("112.20").stop("child-9").build();
        book(position);
        var r = emergencyRow();
        gateway.seedOrder(liveStop("child-9", "TECHA", "9", "89.76"));
        gateway.seedOrder(liveParent("brk-9", "sp-1-202611", "TECHA", "9", "112.20"));
        broker("9", "112.20");
        when(hardTrigger.hardExit(any(), any(), any(), any())).thenReturn(HardTriggerService.HardExitOutcome.SUBMITTED);

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);
        consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY.plusSeconds(600));

        assertThat(gateway.ops).containsExactly("cancel:brk-9");
        verify(hardTrigger, org.mockito.Mockito.times(1)).hardExit(eq(position), eq(new BigDecimal("110")),
                org.mockito.ArgumentMatchers.startsWith("SAVINGS_EMERGENCY"), eq("run-2"));
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.CLOSED_WITH_POSITION);
        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l).contains("op=flatten").contains("outcome=SUBMITTED"));
    }

    @Test
    void aRejectedEmergencyFlattenStaysAndAGonePositionWaitsForReconcile() {
        position = SavingsFixtures.pos(1, "TECHA").qty("9").entry("112.20").stop("child-9").build();
        book(position);
        var r = emergencyRow();
        gateway.seedOrder(liveStop("child-9", "TECHA", "9", "89.76"));
        broker("9", "112.20");
        when(hardTrigger.hardExit(any(), any(), any(), any()))
                .thenReturn(HardTriggerService.HardExitOutcome.FAILED, HardTriggerService.HardExitOutcome.POSITION_GONE);

        assertThat(consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY)).containsEntry("escalated", 1);
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.EMERGENCY_EXIT);
        assertThat(gateway.ops).isEmpty();

        assertThat(consolidator.consolidateStage("c", "run-3", "pass-3", NEXT_DAY)).containsEntry("waiting-reconcile", 1);
        assertThat(row(r.id).status()).isEqualTo(SavingsBuy.EMERGENCY_EXIT);
    }

    @Test
    void theEmergencyStepResyncsTheBookWhenTheBrokerQtyChanged() {
        position = SavingsFixtures.pos(1, "TECHA").qty("9").entry("112.20").stop("child-9").build();
        ExecutorPosition resynced = SavingsFixtures.pos(1, "TECHA").qty("7").entry("112.20").stop("child-9").build();
        when(positionRepo.findById(1L)).thenReturn(position, resynced);
        when(legRepo.findOpenByPosition(1L)).thenReturn(List.of(SavingsFixtures.leg(position)));
        emergencyRow();
        broker("7", "112.20");
        when(hardTrigger.hardExit(any(), any(), any(), any())).thenReturn(HardTriggerService.HardExitOutcome.SUBMITTED);

        consolidator.consolidateStage("c", "run-2", "pass-2", NEXT_DAY);

        verify(positionRepo).syncQty(1L, new BigDecimal("7"));
        verify(legRepo).syncLegQty(10L, new BigDecimal("7"));
        verify(hardTrigger).hardExit(eq(resynced), any(), any(), any());
    }
}
