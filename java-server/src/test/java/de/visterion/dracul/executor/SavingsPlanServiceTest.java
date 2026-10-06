package de.visterion.dracul.executor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.marketdata.FxService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-06 §4, §12 SavingsPlanServiceTest. Account currency EUR, USD→EUR 0.9,
 *  total-budget 100 000 ⇒ month amount 2 000 EUR, per-position cap 8 000, basket cap 50 000. */
class SavingsPlanServiceTest {

    static final Instant PLAN_DAY = Instant.parse("2026-11-02T23:00:00Z");   // Mon, weekday 1

    final FakeExecutionGateway gateway = new FakeExecutionGateway();
    final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    final InMemorySavingsPlanRepository savingsRepo = new InMemorySavingsPlanRepository();
    final ExecutorIndicators indicators = mock(ExecutorIndicators.class);
    final FxService fx = mock(FxService.class);
    final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    final TelegramNotifier telegram = mock(TelegramNotifier.class);
    ListAppender<ILoggingEvent> appender;
    SavingsPlanService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v1.3");
        when(fx.hasRate("USD", "EUR")).thenReturn(true);
        when(fx.convert(BigDecimal.ONE, "USD", "EUR")).thenReturn(new BigDecimal("0.9"));
        gateway.setAccount(new AccountSnapshot(new BigDecimal("50000"), new BigDecimal("50000"), "EUR"));
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(SavingsPlanAudit.class)).addAppender(appender);
        service = service(settings("gtc"));
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(SavingsPlanAudit.class)).detachAndStopAllAppenders();
    }

    static SavingsPlanSettings settings(String tif) {
        return new SavingsPlanSettings(true, new BigDecimal("0.02"), new BigDecimal("0.08"),
                new BigDecimal("0.50"), new BigDecimal("0.02"), 3, LocalTime.of(21, 15), tif, false);
    }

    SavingsPlanService service(SavingsPlanSettings s) {
        return new SavingsPlanService(gateway, positionRepo, legRepo, savingsRepo, indicators, fx,
                ConvictionProfile.defaults(), s,
                new SavingsPlanAudit(decisionRepo, ruleVersions, new ObjectMapper(), telegram),
                TransactionOperations.withoutTransaction(), new BigDecimal("100000"), "USD", 22, 20);
    }

    void book(ExecutorPosition... ps) {
        when(positionRepo.findOpen()).thenReturn(List.of(ps));
        for (ExecutorPosition p : ps) {
            when(legRepo.findOpenByPosition(p.id())).thenReturn(List.of(SavingsFixtures.leg(p)));
        }
    }

    void close(String symbol, String px) {
        when(indicators.levels(symbol, 22, 20)).thenReturn(SavingsFixtures.levels(px));
    }

    List<String> lines(String prefix) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix)).toList();
    }

    List<DecisionLog> decisions() {
        ArgumentCaptor<DecisionLog> c = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, atLeast(0)).insert(c.capture());
        return c.getAllValues();
    }

    SavingsBuy row(long positionId, String month) {
        return savingsRepo.findByMonth(month).stream().filter(b -> b.positionId() == positionId)
                .findFirst().orElseThrow();
    }

    @Test
    void planDayPlacesEqualSharesAtTheMarketableLimit() {
        book(SavingsFixtures.pos(1, "TECHA").qty("10").entry("100").build(),
                SavingsFixtures.pos(2, "TECHB").qty("20").entry("50").build());
        close("TECHA", "110");
        close("TECHB", "45");

        SavingsPlanService.AddResult r = service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(r.placed()).isEqualTo(2);
        BracketRequest a = gateway.placed.get(0);
        assertThat(a.symbol()).isEqualTo("TECHA");
        assertThat(a.qty()).isEqualByComparingTo("9");                 // floor(1000 / 100.98)
        assertThat(a.limitPrice()).isEqualByComparingTo("112.20");     // roundEntry(110 × 1.02)
        assertThat(a.stopLossStop()).isEqualByComparingTo("89.76");    // band −20 % of the limit
        assertThat(a.takeProfitLimit()).isNull();
        assertThat(a.clientRef()).isEqualTo("sp-1-202611");
        assertThat(a.timeInForce()).isEqualTo("gtc");
        BracketRequest b = gateway.placed.get(1);
        assertThat(b.qty()).isEqualByComparingTo("24");                // floor(1000 / 41.31)
        assertThat(b.limitPrice()).isEqualByComparingTo("45.90");
        assertThat(b.stopLossStop()).isEqualByComparingTo("36.72");

        SavingsBuy rowA = row(1, "2026-11");
        assertThat(rowA.status()).isEqualTo(SavingsBuy.PLACED);
        assertThat(rowA.entryOrderId()).startsWith("brk-");
        assertThat(rowA.childStopOrderId()).startsWith("stop-");
        assertThat(rowA.qtyBefore()).isEqualByComparingTo("10");
        assertThat(rowA.avgBefore()).isEqualByComparingTo("100");
        assertThat(rowA.stopBefore()).isEqualByComparingTo("65.00");
        assertThat(rowA.limitEur()).isEqualByComparingTo("100.98");
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("91.18");
        assertThat(savingsRepo.carryOf(2)).isEqualByComparingTo("8.56");
        SavingsMonth m = savingsRepo.findMonth("2026-11");
        assertThat(m.monthAmountEur()).isEqualByComparingTo("2000");
        assertThat(m.candidateCount()).isEqualTo(2);
        assertThat(m.completedAt()).isNotNull();

        // §8a: every status change is a transition line AND a decision row with the deciding values
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("position=1").contains("symbol=TECHA").contains("from=none").contains("to=PLACING")
                .contains("qty=9").contains("limit=112.2").contains("qty_before=10").contains("avg_before=100")
                .contains("stop_before=65").contains("run=run-1").contains("pass=pass-1"));
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("from=PLACING").contains("to=PLACED").contains("entry_order_id=brk-"));
        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l)
                .contains("op=placeBracket").contains("phase=intent").contains("client_ref=sp-1-202611"));
        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l)
                .contains("op=placeBracket").contains("result=accepted").contains("ids=").contains("parent:brk-"));
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("stage=add").contains("outcome=acted").contains("placed=2").contains("eligible=2"));
        assertThat(decisions()).filteredOn(d -> "SAVINGS_ADD".equals(d.action()))
                .extracting(DecisionLog::reasonCode).containsExactly("PLACING", "SAVINGS_ADD", "PLACING", "SAVINGS_ADD");
        DecisionLog placedRow = decisions().stream().filter(d -> "SAVINGS_ADD".equals(d.reasonCode())).findFirst().orElseThrow();
        assertThat(placedRow.triggerType()).isEqualTo("SAVINGS_PLAN");
        assertThat(placedRow.orderJson().path("savings_buy_id").asLong()).isEqualTo(rowA.id());
        assertThat(placedRow.orderJson().path("from").asString()).isEqualTo("PLACING");
        assertThat(placedRow.orderJson().path("to").asString()).isEqualTo("PLACED");
        verify(telegram).notifyDigest(org.mockito.ArgumentMatchers.contains("Sparplan 2026-11"));
    }

    @Test
    void tifSwitchDayIsSentToTheBroker() {
        service = service(settings("day"));
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(gateway.placed).singleElement().satisfies(b -> assertThat(b.timeInForce()).isEqualTo("day"));
        assertThat(row(1, "2026-11").tif()).isEqualTo("day");
    }

    @Test
    void basketCapLimitsTheAmountAMissingCloseIsValuedAtCostAndCapSkipsLogTheirValues() {
        book(SavingsFixtures.pos(1, "TECHA").qty("10").entry("100").build(),      // 990 EUR
                SavingsFixtures.pos(3, "TECHC").qty("100").entry("400").build(),  // no close: 36 000 at cost
                SavingsFixtures.pos(4, "TECHD").qty("120").entry("100").build()); // 11 880 ≥ 8 000
        close("TECHA", "110");
        close("TECHC", null);
        close("TECHD", "110");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(savingsRepo.findMonth("2026-11").monthAmountEur()).isEqualByComparingTo("1130");   // 50 000 − 48 870
        assertThat(savingsRepo.findMonth("2026-11").candidateCount()).isEqualTo(1);
        assertThat(gateway.placed).singleElement().satisfies(b -> assertThat(b.qty()).isEqualByComparingTo("11"));
        assertThat(row(3, "2026-11").skipReason()).isEqualTo("DATA");
        assertThat(row(4, "2026-11").skipReason()).isEqualTo("CAP");
        assertThat(lines("savings-plan skip")).anySatisfy(l -> assertThat(l)
                .contains("reason=CAP").contains("value_eur=11880>=cap_eur=8000").contains("weight=0.1188>=0.08"));
        assertThat(lines("savings-plan skip")).anySatisfy(l -> assertThat(l)
                .contains("reason=DATA").contains("close=null"));
        assertThat(lines("savings-plan transition")).anySatisfy(l -> assertThat(l)
                .contains("position=4").contains("from=none").contains("to=SKIPPED").contains("reason=CAP"));
        // Fix round 1 Minor 3: the skipped counts render as {REASON:n,...} — no Map.toString()
        // space surviving into plain() as a stray "_".
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("skipped={CAP:1,DATA:1}"));
    }

    @Test
    void perPositionCapLimitsTheShare() {
        book(SavingsFixtures.pos(5, "TECHE").qty("80").entry("100").build());   // 80 × 105 × 0.9 = 7 560
        close("TECHE", "105");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        // share min(2 000, 8 000 − 7 560 = 440); limit 107.10 → 96.39 EUR; floor(440 / 96.39) = 4
        assertThat(gateway.placed).singleElement().satisfies(b -> assertThat(b.qty()).isEqualByComparingTo("4"));
        assertThat(savingsRepo.carryOf(5)).isEqualByComparingTo("54.44");
    }

    @Test
    void capFullCompletesTheMonthWithoutBuyRows() {
        book(SavingsFixtures.pos(6, "TECHF").qty("500").entry("100").build());  // 50 400 ≥ 50 000
        close("TECHF", "112");

        SavingsPlanService.AddResult r = service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(r.why()).isEqualTo("cap-full");
        assertThat(savingsRepo.rows).isEmpty();
        assertThat(savingsRepo.findMonth("2026-11").completedAt()).isNotNull();
        assertThat(decisions()).extracting(DecisionLog::action, DecisionLog::reasonCode)
                .contains(org.assertj.core.groups.Tuple.tuple("SAVINGS_SKIP", "CAP_FULL"));
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("outcome=did-nothing").contains("why=cap-full"));
    }

    @Test
    void missingFxFailsTheStageClosedAndKeepsTheMonthOpen() {
        when(fx.hasRate("USD", "EUR")).thenReturn(false);
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");

        SavingsPlanService.AddResult r = service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(r.why()).isEqualTo("fx-missing");
        assertThat(savingsRepo.rows).isEmpty();
        assertThat(savingsRepo.findMonth("2026-11")).isNull();
        assertThat(decisions()).extracting(DecisionLog::reasonCode).containsExactly("FX_MISSING");
    }

    @Test
    void nullAccountSkipsTheStageAndKeepsTheMonthOpen() {
        gateway.setAccount(null);
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");

        SavingsPlanService.AddResult r = service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(r.why()).isEqualTo("no-account");
        assertThat(savingsRepo.rows).isEmpty();
        assertThat(decisions()).extracting(DecisionLog::reasonCode).containsExactly("NO_ACCOUNT");
    }

    @Test
    void carryAccruesBelowOneShareAndBuysNextMonth() {
        book(SavingsFixtures.pos(7, "TECHG").qty("2").entry("1500").build());   // 2 × 2 500 × 0.9 = 4 500
        close("TECHG", "2500");                                                // limit 2 550 → 2 295 EUR

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(row(7, "2026-11").status()).isEqualTo(SavingsBuy.SKIPPED);
        assertThat(row(7, "2026-11").skipReason()).isEqualTo("CARRY");
        assertThat(savingsRepo.carryOf(7)).isEqualByComparingTo("2000");
        assertThat(gateway.placed).isEmpty();

        Instant december = Instant.parse("2026-12-01T23:00:00Z");              // Tue, weekday 1
        savingsRepo.now = december;
        service.addStage("c", "run-2", "pass-2", december);

        assertThat(gateway.placed).singleElement().satisfies(b -> assertThat(b.qty()).isEqualByComparingTo("1"));
        assertThat(savingsRepo.carryOf(7)).isEqualByComparingTo("1705");      // 4 000 − 2 295
    }

    @Test
    void lazyCleanupDropsTheCarryOfGonePositions() {
        savingsRepo.carry.put(77L, new BigDecimal("500"));
        savingsRepo.staleCarryPositions.add(77L);
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(savingsRepo.carry).doesNotContainKey(77L);
    }

    @Test
    void everyEligibilityReasonIsARowWithTheFirstFailingReason() {
        ExecutorPosition twoLegs = SavingsFixtures.pos(15, "TECHF").build();
        book(SavingsFixtures.pos(10, "TECHA").unfilled().build(),
                SavingsFixtures.pos(11, "TECHB").pendingExit("HARD_CATASTROPHE").build(),
                SavingsFixtures.pos(12, "TECHC").trimCount(1).build(),
                SavingsFixtures.pos(13, "TECHD").pendingTrim("trim-13").build(),
                SavingsFixtures.pos(14, "TECHE").catastrophe("synthetic fraud").build(),
                twoLegs,
                SavingsFixtures.pos(16, "SYNTH").build());
        when(legRepo.findOpenByPosition(15L)).thenReturn(List.of(SavingsFixtures.leg(twoLegs),
                new ExecutorPositionLeg(151L, 15L, 2, "brk-15b", "stop-15b", BigDecimal.ONE,
                        ExecutorPositionLeg.OPEN, null, null, null)));
        savingsRepo.seed("2026-10", 16L, "SYNTH", SavingsBuy.PLACED, "3", "100", "90", "10", "100", "65",
                Instant.parse("2026-10-30T23:00:00Z"));
        for (String s : List.of("TECHA", "TECHB", "TECHC", "TECHD", "TECHE", "TECHF", "SYNTH")) close(s, "110");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(row(10, "2026-11").skipReason()).isEqualTo("UNFILLED");
        assertThat(row(11, "2026-11").skipReason()).isEqualTo("PENDING_EXIT");
        assertThat(row(12, "2026-11").skipReason()).isEqualTo("TRIMMED");
        assertThat(row(13, "2026-11").skipReason()).isEqualTo("TRIMMED");
        assertThat(row(14, "2026-11").skipReason()).isEqualTo("CATASTROPHE");
        assertThat(row(15, "2026-11").skipReason()).isEqualTo("LEGS");
        assertThat(row(16, "2026-11").skipReason()).isEqualTo("IN_FLIGHT");
        assertThat(gateway.placed).isEmpty();
        assertThat(savingsRepo.carry).isEmpty();
        assertThat(lines("savings-plan skip")).anySatisfy(l -> assertThat(l).contains("reason=LEGS").contains("open_legs=2"));
        assertThat(lines("savings-plan skip")).anySatisfy(l -> assertThat(l).contains("reason=IN_FLIGHT").contains("in_flight_status=PLACED"));
        assertThat(savingsRepo.findMonth("2026-11").completedAt()).isNotNull();
    }

    /** §4.3 / R2 M7: addStage reads findOpen itself — a TARGET_HALF or a catastrophe flatten earlier
     *  in the same pass is visible there (trim_count / pending_exit_reason) and blocks the add. */
    @Test
    void aSamePassTargetHalfOrCatastropheFlattenBlocksTheAdd() {
        book(SavingsFixtures.pos(20, "TECHA").trimCount(1).build(),
                SavingsFixtures.pos(21, "TECHB").pendingExit("HARD_CATASTROPHE").build());
        close("TECHA", "131");
        close("TECHB", "110");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(gateway.placed).isEmpty();
        assertThat(row(20, "2026-11").skipReason()).isEqualTo("TRIMMED");
        assertThat(row(21, "2026-11").skipReason()).isEqualTo("PENDING_EXIT");
    }

    @Test
    void aLogicalStopAboveTheCloseSkips() {
        book(SavingsFixtures.pos(22, "TECHA").qty("40").entry("200").build());   // 40 × 60 × 0.9 = 2 160
        close("TECHA", "60");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(row(22, "2026-11").skipReason()).isEqualTo("STOP_ABOVE_CLOSE");
        assertThat(lines("savings-plan skip")).anySatisfy(l -> assertThat(l)
                .contains("reason=STOP_ABOVE_CLOSE").contains(">=close=60"));
        assertThat(savingsRepo.carry).isEmpty();
    }

    @Test
    void cashIsDecrementedAndTheRestIsNoCash() {
        gateway.setAccount(new AccountSnapshot(new BigDecimal("1000"), new BigDecimal("1000"), "EUR"));
        book(SavingsFixtures.pos(1, "TECHA").qty("10").entry("100").build(),
                SavingsFixtures.pos(2, "TECHB").qty("20").entry("50").build());
        close("TECHA", "110");
        close("TECHB", "45");

        service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(gateway.placed).singleElement().satisfies(b -> assertThat(b.symbol()).isEqualTo("TECHA"));
        assertThat(row(2, "2026-11").skipReason()).isEqualTo("NO_CASH");
        assertThat(savingsRepo.carryOf(2)).isEqualByComparingTo("1000");
        assertThat(lines("savings-plan skip")).anySatisfy(l -> assertThat(l)
                .contains("reason=NO_CASH").contains("cost_eur=991.44>cash_eur=91.18"));
    }

    @Test
    void aDeterminateRejectIsRejectedWithTheCarryKept() {
        gateway.rejectPlaceBracketWith = new BrokerRejectedException("no", "INSUFFICIENT_FUNDS", List.of());
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");

        SavingsPlanService.AddResult r = service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(row(1, "2026-11").status()).isEqualTo(SavingsBuy.REJECTED);
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("2000");
        assertThat(decisions()).extracting(DecisionLog::reasonCode).contains("SAVINGS_ADD_REJECTED");
        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l)
                .contains("result=rejected").contains("code=INSUFFICIENT_FUNDS"));
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).startsWith("savings-plan escalation").contains("code=SAVINGS_ADD_REJECTED");
        });
        // Fix round 1 Important #1: a pass whose only candidate was a determinate reject wrote a
        // PLACING row and made a broker call — the stage line must say `acted`, never `did-nothing`.
        assertThat(r.outcome()).isEqualTo("acted");
        assertThat(r.why()).isEqualTo("-");
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("outcome=acted").contains("why=-").contains("rejected=1").contains("escalated=1"));
    }

    @Test
    void anIndeterminatePlaceStaysPlacing() {
        gateway.rejectPlaceBracketWith = new BrokerUnavailableException("read timeout");
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");

        SavingsPlanService.AddResult r = service.addStage("c", "run-1", "pass-1", PLAN_DAY);

        assertThat(row(1, "2026-11").status()).isEqualTo(SavingsBuy.PLACING);
        // one candidate: share 2 000, qty floor(2 000 / 100.98) = 19, carry 2 000 − 1 918.62 stays decremented
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("81.38");
        assertThat(decisions()).extracting(DecisionLog::reasonCode).contains("SAVINGS_ADD_INDETERMINATE");
        assertThat(lines("savings-plan broker")).anySatisfy(l -> assertThat(l).contains("result=indeterminate"));
        // Fix round 1 Important #1: an indeterminate placement is also `acted`, with an escalated count.
        assertThat(r.outcome()).isEqualTo("acted");
        assertThat(r.why()).isEqualTo("-");
        assertThat(lines("savings-plan stage")).singleElement().satisfies(l -> assertThat(l)
                .contains("outcome=acted").contains("why=-").contains("rejected=0").contains("escalated=1"));
    }

    @Test
    void missedIsRaisedOnWeekdayFourExactlyOnce() {
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");
        Instant thursday = Instant.parse("2026-11-05T23:00:00Z");

        service.addStage("c", "run-1", "pass-1", thursday);
        service.addStage("c", "run-2", "pass-2", thursday);

        assertThat(decisions()).filteredOn(d -> "SAVINGS_PLAN_MISSED".equals(d.reasonCode())).hasSize(1);
        assertThat(savingsRepo.findMonth("2026-11").missedAlertedAt()).isNotNull();
        assertThat(gateway.placed).isEmpty();
    }

    /** §8a / §12: a stage that does nothing says why — never silent, never a WARN. */
    @Test
    void aStageThatDoesNothingSaysWhy() {
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");

        service.addStage("c", "run-1", "pass-1", Instant.parse("2026-11-02T15:00:00Z"));   // before the window
        // Fix round 1 Minor 6: MISSED now covers every weekday >= catchUp+1 (never just the exact
        // day), so a weekday that used to demo "not-plan-day" (NONE) no longer exists for a normal
        // catch-up config — demo "month-done" instead, the other did-nothing reason with no WARN.
        savingsRepo.months.put("2026-11", new SavingsMonth("2026-11", new BigDecimal("2000"), 1,
                savingsRepo.now, null));
        service.addStage("c", "run-2", "pass-2", PLAN_DAY);
        service(SavingsPlanSettings.defaults()).addStage("c", "run-3", "pass-3", PLAN_DAY);

        assertThat(lines("savings-plan stage")).satisfiesExactly(
                l -> assertThat(l).contains("stage=add").contains("outcome=did-nothing").contains("why=outside-window"),
                l -> assertThat(l).contains("outcome=did-nothing").contains("why=month-done"),
                l -> assertThat(l).contains("outcome=did-nothing").contains("why=disabled"));
        assertThat(appender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
        assertThat(gateway.placed).isEmpty();
    }

    /** Fix round 1 Minor 6: the first in-window pass at a weekday index >= catchUp+1 still raises
     *  SAVINGS_PLAN_MISSED, even when no pass ran on the exact catchUp+1 day — the usual cause of a
     *  miss is exactly an outage on that day. Still once per month. */
    @Test
    void missedAlarmStillFiresWhenTheOnlyInWindowPassIsAfterTheExactMissDay() {
        book(SavingsFixtures.pos(1, "TECHA").build());
        close("TECHA", "110");
        Instant friday = Instant.parse("2026-11-06T23:00:00Z");   // weekday index 5 — no pass ran on index 4

        SavingsPlanService.AddResult r = service.addStage("c", "run-1", "pass-1", friday);

        assertThat(r.why()).isEqualTo("missed");
        assertThat(decisions()).filteredOn(d -> "SAVINGS_PLAN_MISSED".equals(d.reasonCode())).hasSize(1);
        assertThat(savingsRepo.findMonth("2026-11").missedAlertedAt()).isNotNull();
        assertThat(gateway.placed).isEmpty();

        service.addStage("c", "run-2", "pass-2", friday);
        assertThat(decisions()).filteredOn(d -> "SAVINGS_PLAN_MISSED".equals(d.reasonCode())).hasSize(1);
    }

    @Test
    void aCatchUpReusesTheMonthAmountAndNeverAccruesTwice() {
        ExecutorPosition a = SavingsFixtures.pos(1, "TECHA").build();
        book(a);
        close("TECHA", "110");
        service.addStage("c", "run-1", "pass-1", PLAN_DAY);
        assertThat(savingsRepo.carryOf(1)).isEqualByComparingTo("81.38");          // share 2 000, 19 shares

        // weekday 2: a second, newly eligible position joins; the month amount (2 000, 1 candidate) is reused
        book(a, SavingsFixtures.pos(2, "TECHB").qty("20").entry("50").build());
        close("TECHB", "45");
        savingsRepo.months.put("2026-11", new SavingsMonth("2026-11", new BigDecimal("2000"), 1, null, null));
        Instant tuesday = Instant.parse("2026-11-03T23:00:00Z");
        savingsRepo.now = tuesday;
        service.addStage("c", "run-2", "pass-2", tuesday);

        assertThat(savingsRepo.carryOf(1)).as("TECHA has its row — no second accrual").isEqualByComparingTo("81.38");
        assertThat(savingsRepo.findMonth("2026-11").monthAmountEur()).isEqualByComparingTo("2000");
        assertThat(gateway.placed).hasSize(2);
        assertThat(savingsRepo.findMonth("2026-11").completedAt()).isNotNull();
        // Fix round 1 Minor 2: TECHB joining late must never make the month overpay — its share is
        // capped at what TECHA's row did NOT already commit, not a second full 2 000 share.
        BigDecimal totalCommitted = savingsRepo.findByMonth("2026-11").stream()
                .filter(b -> b.qty() != null && b.qty().signum() > 0 && b.limitEur() != null)
                .map(b -> b.qty().multiply(b.limitEur()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalCommitted).as("total committed across both rows never exceeds the month amount")
                .isLessThanOrEqualTo(new BigDecimal("2000"));
    }

    @Test
    void theLeaseLetsOnePassInAndAStaleLeaseIsTakenOver() {
        assertThat(service.tryLease("pass-a")).isTrue();
        assertThat(service.tryLease("pass-b")).isFalse();
        assertThat(service.leaseHolder()).isEqualTo("pass-a");
        savingsRepo.leaseUntil = savingsRepo.now.minusSeconds(1);
        assertThat(service.tryLease("pass-b")).isTrue();
        service.releaseLease("pass-b");
        assertThat(service.leaseHolder()).isNull();
    }

    /** §5.3 / R3: counted in weekdays (NY trade dates) — a Friday add is not stale before Tuesday;
     *  raised once per row; the stage says why when it raises nothing. */
    @Test
    void aStaleAddIsRaisedOnceAndAFridayAddNotBeforeTuesday() {
        ExecutorPosition p = SavingsFixtures.pos(1, "TECHA").build();
        when(positionRepo.findById(1L)).thenReturn(p);
        var r = savingsRepo.seed("2026-11", 1L, "TECHA", SavingsBuy.PLACED, "9", "112.20", "100.98",
                "10", "100", "65.00", Instant.parse("2026-11-06T23:00:00Z"));          // Friday

        assertThat(service.staleCheck("c", "run-mon", "pass-mon", Instant.parse("2026-11-09T23:00:00Z"))).isZero();
        assertThat(service.staleCheck("c", "run-tue", "pass-tue", Instant.parse("2026-11-10T23:00:00Z"))).isEqualTo(1);
        when(decisionRepo.countByReasonCodeForSavingsBuy("SAVINGS_ADD_STALE", r.id)).thenReturn(1);
        assertThat(service.staleCheck("c", "run-tue2", "pass-tue2", Instant.parse("2026-11-10T23:30:00Z"))).isZero();

        assertThat(decisions()).filteredOn(d -> "SAVINGS_ADD_STALE".equals(d.reasonCode())).hasSize(1);
        assertThat(lines("savings-plan stage")).anySatisfy(l -> assertThat(l)
                .contains("stage=stale").contains("outcome=did-nothing").contains("why=no-stale-rows"));
        assertThat(lines("savings-plan stage")).anySatisfy(l -> assertThat(l)
                .contains("stage=stale").contains("why=already-escalated"));
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith("savings-plan escalation").contains("code=SAVINGS_ADD_STALE").contains("severity=CRITICAL"));
    }
}
