package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.executor.broker.PlacedBracket;
import de.visterion.dracul.executor.broker.RestoredLeg;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.position.PositionContextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutorWebhookControllerTest {

    private static final String BEARER = "Bearer tkn";

    private static final BigDecimal BUFFER_ONE = BigDecimal.ONE;
    private static final BigDecimal MAX_BROKER_STOP_PCT = new BigDecimal("0.20");
    /** The whole budget may be risked -> the risk cap never binds and NOTIONAL alone sizes. */
    private static final double RISK_UNBOUND = 1.0;

    private ExecutorSignalRepository signalRepo;
    private ExecutorPositionRepository positionRepo;
    private ExecutorPositionLegRepository legRepo;
    private ExecutorDecisionRepository decisionRepo;
    private ExecutionGateway gateway;
    private ExecutorIndicators executorIndicators;
    private MaintenancePipeline pipeline;
    private DecisionLogRepository decisionLogRepo;
    private CooldownRepository cooldownRepo;
    private RuleVersionProvider ruleVersions;
    private EntryContextAssembler assembler;
    private PositionSizer sizer;
    private SignalRanker ranker;
    private Tranche2Detector tranche2Detector;
    private TelegramNotifier telegram;
    private ExecutorNotifier executorNotifier;
    private PositionContextRepository positionContextRepo;
    private PatternRepository patternRepo;
    private JsonMapper mapper;

    /** Fixed at 42s after every test signal's createdAt ("2026-07-01T00:00:00Z"), so
     *  latency.signal_to_decision_seconds is deterministic across tests. */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T00:00:42Z");
    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private ExecutorWebhookController controller;

    @BeforeEach
    void setUp() {
        signalRepo = mock(ExecutorSignalRepository.class);
        positionRepo = mock(ExecutorPositionRepository.class);
        legRepo = mock(ExecutorPositionLegRepository.class);
        decisionRepo = mock(ExecutorDecisionRepository.class);
        gateway = mock(ExecutionGateway.class);
        executorIndicators = mock(ExecutorIndicators.class);
        pipeline = mock(MaintenancePipeline.class);
        decisionLogRepo = mock(DecisionLogRepository.class);
        cooldownRepo = mock(CooldownRepository.class);
        ruleVersions = mock(RuleVersionProvider.class);
        assembler = mock(EntryContextAssembler.class);
        sizer = new PositionSizer(); // pure, real instance
        ranker = new SignalRanker(); // pure, real instance
        tranche2Detector = mock(Tranche2Detector.class);
        telegram = mock(TelegramNotifier.class);
        executorNotifier = mock(ExecutorNotifier.class);
        positionContextRepo = mock(PositionContextRepository.class);
        patternRepo = mock(PatternRepository.class);
        when(patternRepo.findEnforced()).thenReturn(List.of());
        mapper = JsonMapper.builder().build();

        when(executorIndicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        when(assembler.assemble(any())).thenReturn(happyContext());
        when(assembler.assembleForSymbol(any())).thenReturn(happyContext());

        controller = controllerWith(BUFFER_ONE, sizer, tranche2Detector);
    }

    /** The production wiring under test: buffer 1.0 ATR, 20 % proximity cap, 1 % risk budget. */
    private ExecutorWebhookController controllerWith(BigDecimal bufferAtr, PositionSizer theSizer,
            Tranche2Detector theDetector) {
        return controllerWith(bufferAtr, theSizer, theDetector, 0.01, 0.06);
    }

    /** The identity wiring (buffer 0): the bracket then carries the logical stop VERBATIM, so a
     *  test that pins the stop ROUNDING/clamping sequence keeps asserting about rounding and not
     *  about the broker-stop offset, which is a separate concern. */
    private ExecutorWebhookController stopRoundingController() {
        return controllerWith(BigDecimal.ZERO, sizer, tranche2Detector);
    }

    /** {@link #controllerWith} with an explicit per-trade risk fraction and heat pct.
     *  {@code riskPct} 1.0 makes the whole budget riskable, i.e. the risk cap never binds and the
     *  NOTIONAL cap alone decides the quantity — the pre-SP1 sizing rule, which the notional /
     *  price-rounding tests are about. */
    private ExecutorWebhookController controllerWith(BigDecimal bufferAtr, PositionSizer theSizer,
            Tranche2Detector theDetector, double riskPct, double heatPct) {
        return new ExecutorWebhookController(
                signalRepo, positionRepo, legRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, theSizer, ranker, theDetector, telegram, executorNotifier,
                positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, heatPct, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD",
                bufferAtr, MAX_BROKER_STOP_PCT, riskPct, 5, MechanismBudget.none(),
                fixedClock);
    }

    /** EntryContext with an explicit short ATR, so atrEff = max(atr, atrShort) differs from atr. */
    private static EntryContext withPriceAtrAndShort(EntryContext c, BigDecimal price,
            BigDecimal atr, BigDecimal atrShort) {
        BigDecimal atrEff = atrShort == null ? atr : atr.max(atrShort);
        return new EntryContext(c.account(), price, atr, c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), atrShort, atrEff, c.openExposureByMechanism());
    }

    /** The ENTER row (the one carrying order_json), out of however many decision_log rows the
     *  call produced. */
    private DecisionLog enterLog() {
        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().stream()
                .filter(d -> "ENTER".equals(d.action()))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no ENTER decision_log row was written"));
    }

    // -------------------------------------------------------------------
    // EntryContext fixtures
    // -------------------------------------------------------------------

    /**
     * A fully-populated, all-vetos-pass {@link EntryContext}: price=100, atr=2, no swingLow, a
     * generous ADV, empty book, full budget headroom. With this fixture the {@link PositionSizer}
     * (real instance) computes qty=10 (tranche 1000 / price 100) and a BUY stop window of
     * [93.5, 95] — matched to the request bodies below that use {@code stop_price:95}.
     */
    private static EntryContext happyContext() {
        return new EntryContext(
                new AccountSnapshot(new BigDecimal("10000"), new BigDecimal("10000"), "USD"),
                new BigDecimal("100"),
                new BigDecimal("2"),
                null,
                new BigDecimal("500000"),
                new BigDecimal("101"),
                "TECH",
                List.of(),
                List.of(),
                List.of(),
                0,
                0L,
                new BigDecimal("1000"),
                new BigDecimal("10000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Map.of(),
                BigDecimal.ONE,
                List.of(),
                "USD", null, new BigDecimal("2"), Map.of());
    }

    private static EntryContext withMissing(EntryContext c, List<String> missing) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                missing, c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    private static EntryContext withOpenPositions(EntryContext c, List<ExecutorPosition> positions) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), positions, c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    private static EntryContext withEntriesThisWeek(EntryContext c, int entriesThisWeek) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), entriesThisWeek, c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    private static EntryContext withPendingSignals(EntryContext c, List<ExecutorSignal> pending) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                pending, c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    private static EntryContext withSignalAge(EntryContext c, long ageTradingDays) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), ageTradingDays, c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    private static EntryContext withTrancheAmount(EntryContext c, BigDecimal trancheAmount) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), trancheAmount,
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    /** Also raises adv20Notional to keep the LIQUIDITY veto's adv-multiple check satisfied
     *  (adv20Notional must be >= trancheAmount * advMultiple, advMultiple=200 in this test's
     *  controller wiring) — a fixture that only bumps trancheAmount without this would trip
     *  LIQUIDITY for an unrelated reason. */
    private static EntryContext withTrancheAmountAndAdv(EntryContext c, BigDecimal trancheAmount) {
        EntryContext withTranche = withTrancheAmount(c, trancheAmount);
        return new EntryContext(withTranche.account(), withTranche.price(), withTranche.atr(),
                withTranche.swingLow(), trancheAmount.multiply(BigDecimal.valueOf(200 * 2L)),
                withTranche.dayHigh(), withTranche.candidateSector(), withTranche.openPositions(),
                withTranche.activeCooldowns(), withTranche.pendingSignals(), withTranche.entriesThisWeek(),
                withTranche.signalAgeTradingDays(), withTranche.trancheAmount(), withTranche.totalBudget(),
                withTranche.openExposure(), withTranche.openHeat(), withTranche.openMechanisms(),
                withTranche.fxToAccount(), withTranche.missing(), withTranche.quoteCurrency(), withTranche.atrShort(), withTranche.atrEff(), withTranche.openExposureByMechanism());
    }

    private static EntryContext withPrice(EntryContext c, BigDecimal price) {
        return new EntryContext(c.account(), price, c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    private static EntryContext withPriceAndAtr(EntryContext c, BigDecimal price, BigDecimal atr) {
        return new EntryContext(c.account(), price, atr, c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), null, atr, c.openExposureByMechanism());
    }

    private static EntryContext withOpenHeat(EntryContext c, BigDecimal openHeat) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), openHeat, c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency(), c.atrShort(), c.atrEff(), c.openExposureByMechanism());
    }

    private static EntryContext unavailableContext() {
        return new EntryContext(null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), 0, -1L, null, null, null, null,
                Map.of(), BigDecimal.ONE, List.of("price", "atr"), "USD", null, null, Map.of());
    }

    private ExecutorPosition openPosition(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop) {
        return new ExecutorPosition(id, "depot-1", symbol, side, new BigDecimal("10"),
                entry, initialStop, initialStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null, false, null, null);
    }

    /** {@link #openPosition} with the entry confirmed filled at the broker. */
    private ExecutorPosition filledPosition(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop, String entryFilledAt) {
        ExecutorPosition p = openPosition(id, symbol, side, entry, initialStop);
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), null, entryFilledAt);
    }

    /** Same fixture as {@link #openPosition} but with an explicit {@code qty} and
     *  {@code trimCount} for scale-out/ladder tests. */
    private ExecutorPosition openPosition(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop, BigDecimal qty, int trimCount) {
        return new ExecutorPosition(id, "depot-1", symbol, side, qty,
                entry, initialStop, initialStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null, null,
                null, null, null, null, trimCount, null, null, null, null, null, null, false, null, null);
    }

    /** Two-tranche position, tranche-2 limit still working: {@code qty} is what the broker HOLDS
     *  (tranche 1 only), the tranche-2 leg ids are set. Mirrors the prod STT/OFG shape. */
    private ExecutorPosition unfilledTranche2Position(long id, String symbol, BigDecimal heldQty) {
        return new ExecutorPosition(id, "depot-1", symbol, "BUY", heldQty,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 2, null,
                List.of("X"), "sig-1", "hunter", "2026-06-01", null, "OPEN", "2000000001",
                new BigDecimal("100"), null, 0, null, null, null, null, "2000000002",
                null, null, "2000000003", "2000000004", 0, null, null, null, null, null, null, false, null, null);
    }

    @Test
    void exitPosition_onPositionWithWorkingTranche2_trimsAgainstHeldSharesOnly() {
        // BUG-S9 (prod 2026-08-06): SYNA was booked at 12 shares — 6 held plus an intended
        // tranche-2 6 whose limit was still Working — while the broker held 6. A 0.5 trim then
        // computed its remainder from 12 and left the book claiming 6 shares against 3 held.
        // With `qty` meaning shares HELD, the book carries 6 and the trim books a remainder of 3.
        ExecutorPosition open = unfilledTranche2Position(50L, "SYNA", new BigDecimal("6"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("SYNA"), any()))
                .thenReturn(new CloseResult(new BigDecimal("3"), new BigDecimal("3"),
                        new BigDecimal("104"), "2000000005", List.of(), false));

        JsonNode body = json("""
                {"symbol":"SYNA","fraction":0.5,"reason":"SOFT_EXIT"}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("trimmed")).isEqualTo(true);
        // 6 held, 0.5 trim -> 3 closed, 3 remaining. Against the pre-fix book (12) this was
        // 6 closed / 6 remaining, i.e. a trim sized on 6 shares that did not exist.
        assertThat(((BigDecimal) output.get("qty_remaining"))).isEqualByComparingTo("3");

        ArgumentCaptor<BigDecimal> remainingCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).recordTrim(eq(50L), remainingCaptor.capture(), eq(1), any(), anyBoolean());
        assertThat(remainingCaptor.getValue()).isEqualByComparingTo("3");
    }

    private ExecutorSignal signal(String signalId, double confidence, BigDecimal referencePrice) {
        return signal(signalId, confidence, referencePrice, "PENDING");
    }

    private ExecutorSignal signal(String signalId, double confidence, BigDecimal referencePrice, String status) {
        return new ExecutorSignal(signalId, "hunter", "v1", "ACME", "LONG",
                confidence, "mechanism", List.of("X"), "3m", referencePrice,
                status, "2026-07-01T00:00:00Z");
    }

    private ExecutorSignal signal(String signalId, double confidence, BigDecimal referencePrice,
            String status, String mechanism) {
        return new ExecutorSignal(signalId, "hunter", "v1", "ACME", "LONG",
                confidence, mechanism, List.of("X"), "3m", referencePrice,
                status, "2026-07-01T00:00:00Z");
    }

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    /** Builds a controller identical to {@link #controller} but wired with a caller-supplied
     *  {@link PositionSizer} — used to force a defensive null stop window (real
     *  {@link PositionSizer} never returns one; only a mock can simulate a broken server window). */
    private ExecutorWebhookController controllerWithSizer(PositionSizer customSizer) {
        return new ExecutorWebhookController(
                signalRepo, positionRepo, legRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, customSizer, ranker, tranche2Detector, telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD",
                BUFFER_ONE, MAX_BROKER_STOP_PCT, 0.01, 5, MechanismBudget.none(), fixedClock);
    }

    /** Builds a controller identical to {@link #controller} but with a lower LIQUIDITY min-price
     *  floor — needed for sub-$5 fixtures (e.g. the degenerate-window regression case, which is
     *  anchored to the empirically-verified BUY 1.50/ATR 0.03/swingLow 1.399 constants and cannot
     *  be rescaled without changing whether the window is actually degenerate). */
    private ExecutorWebhookController controllerWithMinPrice(BigDecimal minPrice) {
        return new ExecutorWebhookController(
                signalRepo, positionRepo, legRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, sizer, ranker, tranche2Detector, telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, minPrice, 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD",
                BUFFER_ONE, MAX_BROKER_STOP_PCT, 0.01, 5, MechanismBudget.none(), fixedClock);
    }

    /** Builds a controller identical to {@link #controller} but wired with the REAL
     *  {@link Tranche2Detector} instead of the mock — needed to exercise the actual
     *  {@code R_CONFIRMED} comparison (the field-level mock always returns whatever the test
     *  stubs, which cannot prove the controller passes the RAW price into it). */
    private ExecutorWebhookController controllerWithRealTranche2Detector() {
        return new ExecutorWebhookController(
                signalRepo, positionRepo, legRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, sizer, ranker, new Tranche2Detector(), telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD",
                BUFFER_ONE, MAX_BROKER_STOP_PCT, 0.01, 5, MechanismBudget.none(), fixedClock);
    }

    /** Builds a controller identical to {@link #controller} but with a caller-supplied
     *  {@code heatPct}, so a HEAT_LIMIT boundary can be placed exactly between the raw and the
     *  tick-rounded {@code newRiskAccountCcy} for a given price/stop pair. */
    private ExecutorWebhookController controllerWithHeatPct(double heatPct) {
        return controllerWith(BUFFER_ONE, sizer, tranche2Detector, 0.01, heatPct);
    }

    /** Full-field {@link ExecutorPosition} builder for tests that need {@code entryDayHigh} and/or
     *  an {@code activeStop} that diverges from {@code initialStop} — {@link #openPosition} always
     *  nulls {@code entryDayHigh} and pins {@code activeStop == initialStop}. */
    private ExecutorPosition positionWithEntryDayHighAndActiveStop(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop, BigDecimal activeStop, BigDecimal entryDayHigh) {
        return new ExecutorPosition(id, "depot-1", symbol, side, new BigDecimal("10"),
                entry, initialStop, activeStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null,
                /* stopOrderId */ null, /* sector */ null, entryDayHigh,
                /* tranche2OrderId */ null, /* tranche2StopOrderId */ null, /* trimCount */ 0,
                /* lowestPrice */ null, /* entryExpiresAt */ null, /* submittedLimitPrice */ null,
                /* pendingExitReason */ null, /* exitOrderId */ null, /* pendingExitFillPrice */ null, false, null,
                "2026-07-02T00:00:00Z");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputOf(ResponseEntity<?> resp) {
        return (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
    }

    // -------------------------------------------------------------------
    // auth
    // -------------------------------------------------------------------

    @Test
    void authRejected() {
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry("Bearer wrong", null, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(gateway, signalRepo, positionRepo, decisionRepo);
    }

    // -------------------------------------------------------------------
    // place-entry: veto rejections — NO broker call
    // -------------------------------------------------------------------

    @Test
    void placeEntry_lowConfidence_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.4, new BigDecimal("100")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("LOW_CONFIDENCE");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo).markStatus("sig-1", "REJECTED");

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void placeEntry_paceLimit_transientStaysPending() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        // entriesThisWeek weit über jedem pace-per-week -> PACE_LIMIT ist der einzige (und erste)
        // fehlschlagende Veto; alles andere aus happyContext() passt.
        when(assembler.assemble(any())).thenReturn(withEntriesThisWeek(happyContext(), 999));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("PACE_LIMIT");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        // Kern des Fixes: transienter Grund -> Signal bleibt PENDING, NICHT REJECTED.
        verify(signalRepo).markStatus("sig-1", "PENDING");
        verify(signalRepo, never()).markStatus("sig-1", "REJECTED");

        // Audit bleibt vollständig: der Reject-Versuch wird weiterhin protokolliert.
        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("PACE_LIMIT");
    }

    @Test
    void placeEntry_lowConfidence_writesRichDecisionLogReject() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.4, new BigDecimal("100")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        controller.placeEntry(BEARER, "run-7", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();

        assertThat(log.triggerType()).isEqualTo("SIGNAL");
        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(log.runId()).isEqualTo("run-7");
        assertThat(log.signalId()).isEqualTo("sig-1");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.orderJson()).isNull();

        JsonNode inputs = log.inputsSnapshot();
        assertThat(inputs).isNotNull();
        for (String key : List.of("signal_confidence", "signal_mechanism", "signal_age_trading_days",
                "order_price", "atr", "book_positions_count", "portfolio_heat_before_pct",
                "portfolio_heat_after_pct", "budget_free", "new_positions_this_week",
                "sector_count_same", "cooldown_status")) {
            assertThat(inputs.has(key)).as("missing key " + key).isTrue();
        }
        assertThat(inputs.path("signal_confidence").asDouble()).isEqualTo(0.4);

        JsonNode vetoResults = log.vetoResults();
        assertThat(vetoResults.isArray()).isTrue();
        assertThat(vetoResults.size()).isEqualTo(17);
        for (JsonNode v : vetoResults) {
            assertThat(v.has("check")).isTrue();
            assertThat(v.has("passed")).isTrue();
            assertThat(v.has("measured")).isTrue();
        }
    }

    @Test
    void placeEntry_lowConfidence_rejectLogCarriesRawAndRoundedPrice() {
        // Production values (TickSizeTest.java:17-22): BUY raw 96.415 tick-rounds to 96.41.
        // A reject row must carry BOTH — order_price (raw, the veto/calibration input) and
        // submitted_price (rounded, what would actually go to the broker) — so an analyst can
        // see the same discrepancy that diagnosed the branch (BROKER_ERROR order_price 96.415
        // followed by BELOW_ANCHOR order_price 96.41 for the same signal_id).
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.4, new BigDecimal("96.415")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":96.415,"stop_price":95}
                """);

        controller.placeEntry(BEARER, "run-7", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();

        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("LOW_CONFIDENCE");

        JsonNode inputs = log.inputsSnapshot();
        assertThat(inputs).isNotNull();
        assertThat(inputs.path("order_price").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("96.415"));
        assertThat(inputs.path("submitted_price").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("96.41"));
    }

    @Test
    void placeEntry_maxPositions_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        List<ExecutorPosition> threeOpen = List.of(
                openPosition(1, "A", "BUY", new BigDecimal("10"), new BigDecimal("9")),
                openPosition(2, "B", "BUY", new BigDecimal("10"), new BigDecimal("9")),
                openPosition(3, "C", "BUY", new BigDecimal("10"), new BigDecimal("9")));
        when(assembler.assemble(any())).thenReturn(withOpenPositions(happyContext(), threeOpen));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("MAX_POSITIONS");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo).markStatus("sig-1", "PENDING");
        verify(signalRepo, never()).markStatus("sig-1", "REJECTED");
    }

    @Test
    void placeEntry_schemaInvalid_unknownSignal() {
        when(signalRepo.findById("ghost")).thenReturn(null);

        JsonNode body = json("""
                {"signal_id":"ghost","symbol":"ZZZ","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(eq("ghost"), eq("ACCEPTED"));

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("SCHEMA_INVALID");
    }

    @Test
    void placeEntry_alreadyProcessed_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100"), "ACCEPTED"));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("DUPLICATE");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(anyString(), anyString());

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("DUPLICATE");
    }

    @Test
    void placeEntry_serverWindowNull_defensiveNoStop() {
        // Real PositionSizer never returns a null window; only a mocked one simulates a broken
        // server window, exercising OrderGuard's defensive NO_STOP path (the only way NO_STOP can
        // still fire post-clamp).
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        PositionSizer brokenSizer = mock(PositionSizer.class);
        when(brokenSizer.stopWindow(any(), any(), any(), any()))
                .thenReturn(new StopWindow(null, null, "broken"));
        when(brokenSizer.size(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Sizing(new BigDecimal("10"), new BigDecimal("5"),
                        new BigDecimal("50"), null, null, true, "broken",
                        new BigDecimal("10"), new BigDecimal("10"), "NOTIONAL", null));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY"}
                """);

        ResponseEntity<?> resp = controllerWithSizer(brokenSizer).placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NO_STOP");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo).markStatus("sig-1", "REJECTED");

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().accepted()).isFalse();
        assertThat(captor.getValue().rejectReason()).isEqualTo("NO_STOP");
    }

    @Test
    void placeEntry_serverWindowNull_writesRichDecisionLog() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        PositionSizer brokenSizer = mock(PositionSizer.class);
        when(brokenSizer.stopWindow(any(), any(), any(), any()))
                .thenReturn(new StopWindow(null, null, "broken"));
        when(brokenSizer.size(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Sizing(new BigDecimal("10"), new BigDecimal("5"),
                        new BigDecimal("50"), null, null, true, "broken",
                        new BigDecimal("10"), new BigDecimal("10"), "NOTIONAL", null));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY"}
                """);

        controllerWithSizer(brokenSizer).placeEntry(BEARER, "run-8", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();

        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("NO_STOP");
        assertThat(log.orderJson()).isNull();
        assertThat(log.inputsSnapshot()).isNotNull();
        assertThat(log.vetoResults().size()).isEqualTo(17);
    }

    // -------------------------------------------------------------------
    // place-entry: stop clamp — risk layer is authoritative over the LLM's proposed stop
    // -------------------------------------------------------------------
    //
    // happyContext(): price=100, atr=2, no swingLow -> BUY stop window [93.5, 95]
    // (stopMin=floor=100-6-0.5=93.5, stopMax=anchor=100-5=95).

    @Test
    void placeEntry_stopInWindow_usedUnchanged() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":94}
                """);

        ResponseEntity<?> resp = stopRoundingController().placeEntry(BEARER, "run-1", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("94");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("stop_clamped").asBoolean()).isFalse();
        assertThat(order.path("proposed_stop").asDouble()).isEqualTo(94.0);
        assertThat(order.path("stop_min").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(95.0);
    }

    @Test
    void placeEntry_stopTooTight_clampedToStopMax() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        // Model stop (98) is closer to price (100) than stopMax (95) allows -> clamp down to 95.
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":98}
                """);

        ResponseEntity<?> resp = stopRoundingController().placeEntry(BEARER, "run-2", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);
        verify(decisionRepo, never()).insert(argThat(d -> "NO_STOP".equals(d.rejectReason())));

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("95");
        // qty is re-sized from the clamped stop (r_per_share=100-95=5 -> risk 1000*0.06... still
        // floored by tranche 1000/100=10 shares, unaffected here, but stop leg reflects the clamp).

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("stop_price").asDouble()).isEqualTo(95.0);
        assertThat(order.path("stop_clamped").asBoolean()).isTrue();
        assertThat(order.path("proposed_stop").asDouble()).isEqualTo(98.0);
        assertThat(order.path("stop_min").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(95.0);
    }

    @Test
    void placeEntry_stopTooWide_clampedToStopMin() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        // Model stop (90) is further from price than stopMin (93.5) allows -> clamp up to 93.5.
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":90}
                """);

        ResponseEntity<?> resp = stopRoundingController().placeEntry(BEARER, "run-3", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("93.5");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("stop_price").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_clamped").asBoolean()).isTrue();
        assertThat(order.path("proposed_stop").asDouble()).isEqualTo(90.0);
        assertThat(order.path("stop_min").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(95.0);
    }

    @Test
    void placeEntry_sellStopClampedIntoWindow() {
        // happyContext(): price=100, atr=2, no swingLow -> SELL stop window [105, 106.5]
        // (stopMin=anchor=100+5=105, stopMax=floor=100+6+0.5=106.5), i.e. above price, mirroring
        // the BUY window used by the clamp tests above.
        ExecutorSignal sellSignal = new ExecutorSignal("sig-1", "hunter", "v1", "ACME", "SELL",
                0.9, "mechanism", List.of("X"), "3m", new BigDecimal("100"), "PENDING",
                "2026-07-01T00:00:00Z");
        when(signalRepo.findById("sig-1")).thenReturn(sellSignal);
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        // Model stop (115) is further above price than stopMax (106.5) allows -> clamp down to 106.5.
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"SELL","stop_price":115}
                """);

        ResponseEntity<?> resp = stopRoundingController().placeEntry(BEARER, "run-sell-1", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);
        verify(decisionRepo, never()).insert(argThat(d -> "NO_STOP".equals(d.rejectReason())));

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().side()).isEqualTo("SELL");
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("106.5");
        // qty is server-side sizer output (tranche 1000 / price 100), sized from the clamped stop.
        assertThat(reqCaptor.getValue().qty()).isEqualByComparingTo("10");

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().initialStop()).isEqualByComparingTo("106.5");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("stop_price").asDouble()).isEqualTo(106.5);
        assertThat(order.path("stop_clamped").asBoolean()).isTrue();
        assertThat(order.path("proposed_stop").asDouble()).isEqualTo(115.0);
        assertThat(order.path("stop_min").asDouble()).isEqualTo(105.0);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(106.5);
    }

    @Test
    void placeEntry_nullStop_clampedToStopMin_noNpe() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        // stop_price entirely omitted -> null proposed stop, clamps to stopMin (93.5), no NPE.
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY"}
                """);

        ResponseEntity<?> resp = stopRoundingController().placeEntry(BEARER, "run-4", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("93.5");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("stop_price").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_clamped").asBoolean()).isTrue();
        assertThat(order.path("proposed_stop").isNull()).isTrue();
        assertThat(order.path("stop_min").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(95.0);
    }

    @Test
    void placeEntry_nullBody_rejectsCleanly() {
        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
    }

    @Test
    void placeEntry_invalidSide_rejectsWithoutSizing() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"LONG","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(assembler, never()).assemble(any());

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        assertThat(captor.getValue().rejectReason()).isEqualTo("SCHEMA_INVALID");
    }

    @Test
    void placeEntry_dataUnavailable_rejectsWithoutSizerOrGateway() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any())).thenReturn(unavailableContext());

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("DATA_UNAVAILABLE");

        verifyNoInteractions(gateway);
        verify(positionRepo, never()).insert(any());
        verify(signalRepo).markStatus("sig-1", "REJECTED");
    }

    @Test
    void placeEntry_dataUnavailable_writesRichDecisionLogWithNullsForMissingSnapshotValues() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any())).thenReturn(unavailableContext());

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        controller.placeEntry(BEARER, "run-9", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();

        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("DATA_UNAVAILABLE");
        assertThat(log.orderJson()).isNull();

        // Genuinely unavailable market data -> null, never fabricated.
        JsonNode inputs = log.inputsSnapshot();
        assertThat(inputs.path("order_price").isNull()).isTrue();
        assertThat(inputs.path("atr").isNull()).isTrue();
        assertThat(inputs.path("signal_age_trading_days").isNull()).isTrue();
        // veto never evaluated past the DATA_UNAVAILABLE short-circuit -> snapshot-derived
        // keys are all null too.
        assertThat(inputs.path("portfolio_heat_before_pct").isNull()).isTrue();
        assertThat(inputs.path("portfolio_heat_after_pct").isNull()).isTrue();
        assertThat(inputs.path("budget_free").isNull()).isTrue();
        assertThat(inputs.path("new_positions_this_week").isNull()).isTrue();
        assertThat(inputs.path("sector_count_same").isNull()).isTrue();
        assertThat(inputs.path("cooldown_status").isNull()).isTrue();

        assertThat(log.vetoResults().size()).isEqualTo(1);
        assertThat(log.vetoResults().get(0).path("check").asString()).startsWith("DATA_UNAVAILABLE");
    }

    @Test
    void placeEntry_trancheTooSmall_rejects() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("2000")));
        when(assembler.assemble(any()))
                .thenReturn(withPrice(happyContext(), new BigDecimal("2000")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":1995}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("TRANCHE_TOO_SMALL");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo).markStatus("sig-1", "REJECTED");
    }

    @Test
    void placeEntry_trancheTooSmall_writesRichDecisionLog() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("2000")));
        when(assembler.assemble(any()))
                .thenReturn(withPrice(happyContext(), new BigDecimal("2000")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":1995}
                """);

        controller.placeEntry(BEARER, "run-10", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();

        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("TRANCHE_TOO_SMALL");
        assertThat(log.orderJson()).isNull();
        assertThat(log.inputsSnapshot()).isNotNull();
        assertThat(log.vetoResults().size()).isEqualTo(17);
    }

    @Test
    void placeEntry_contradictionPair_marksBothSignalsRejected() {
        ExecutorSignal mergerArb = signal("sig-1", 0.9, new BigDecimal("100"), "PENDING", "MERGER_ARB");
        ExecutorSignal contradicting = signal("sig-2", 0.9, new BigDecimal("100"), "PENDING", "PEAD");
        when(signalRepo.findById("sig-1")).thenReturn(mergerArb);
        when(assembler.assemble(any()))
                .thenReturn(withPendingSignals(happyContext(), List.of(contradicting)));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("CONTRADICTION");

        verify(gateway, never()).placeBracket(any(), any());
        verify(signalRepo).markStatus("sig-1", "REJECTED");
        verify(signalRepo).markStatus("sig-2", "REJECTED");

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, times(2)).insert(captor.capture());
        List<ExecutorDecision> decisions = captor.getAllValues();
        assertThat(decisions).extracting(ExecutorDecision::signalId).containsExactlyInAnyOrder("sig-1", "sig-2");
        ExecutorDecision other = decisions.stream().filter(d -> "sig-2".equals(d.signalId())).findFirst().orElseThrow();
        assertThat(other.rationale()).contains("contradiction pair with sig-1");
        assertThat(other.symbol()).isEqualTo("ACME");
    }

    @Test
    void placeEntry_expiredSignalContradictingPeer_stillRejectsPeer() {
        // Regression pin for the SIGNAL_EXPIRED reorder: signal sig-1 is BOTH expired (age 6 > 5)
        // AND contradicts a fresh pending peer sig-2. firstFailure is now SIGNAL_EXPIRED (catalog #3),
        // not CONTRADICTION (#10) — but the peer co-rejection must still fire (decoupled from firstFailure).
        ExecutorSignal mergerArb = signal("sig-1", 0.9, new BigDecimal("100"), "PENDING", "MERGER_ARB");
        ExecutorSignal contradicting = signal("sig-2", 0.9, new BigDecimal("100"), "PENDING", "PEAD");
        when(signalRepo.findById("sig-1")).thenReturn(mergerArb);
        when(assembler.assemble(any()))
                .thenReturn(withSignalAge(withPendingSignals(happyContext(), List.of(contradicting)), 6));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SIGNAL_EXPIRED"); // entering signal's own reason

        verify(gateway, never()).placeBracket(any(), any());
        verify(signalRepo).markStatus("sig-1", "REJECTED"); // SIGNAL_EXPIRED is terminal
        verify(signalRepo).markStatus("sig-2", "REJECTED"); // peer still co-rejected despite firstFailure != CONTRADICTION

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, times(2)).insert(captor.capture());
        ExecutorDecision peer = captor.getAllValues().stream()
                .filter(d -> "sig-2".equals(d.signalId())).findFirst().orElseThrow();
        assertThat(peer.rationale()).contains("contradiction pair with sig-1");
        assertThat(peer.rejectReason()).isEqualTo("CONTRADICTION"); // peer row labeled by its actual cause
    }

    @Test
    void placeEntry_transientCapWithContradictingPeer_leavesPeerUntouched() {
        // Regression pin: when the entering signal is only DEFERRED by a transient cap (MAX_POSITIONS),
        // a co-existing contradiction must NOT co-reject the peer — otherwise the deferred signal could
        // enter on a later run after its peer was killed (order-dependent, breaks "trade neither").
        ExecutorSignal mergerArb = signal("sig-1", 0.9, new BigDecimal("100"), "PENDING", "MERGER_ARB");
        ExecutorSignal contradicting = signal("sig-2", 0.9, new BigDecimal("100"), "PENDING", "PEAD");
        when(signalRepo.findById("sig-1")).thenReturn(mergerArb);
        List<ExecutorPosition> threeOpen = List.of(
                openPosition(1, "A", "BUY", new BigDecimal("10"), new BigDecimal("9")),
                openPosition(2, "B", "BUY", new BigDecimal("10"), new BigDecimal("9")),
                openPosition(3, "C", "BUY", new BigDecimal("10"), new BigDecimal("9")));
        when(assembler.assemble(any())).thenReturn(
                withOpenPositions(withPendingSignals(happyContext(), List.of(contradicting)), threeOpen));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("MAX_POSITIONS"); // transient firstFailure

        verify(gateway, never()).placeBracket(any(), any());
        verify(signalRepo).markStatus("sig-1", "PENDING");            // deferred, not terminal
        verify(signalRepo, never()).markStatus(eq("sig-1"), eq("REJECTED"));
        verify(signalRepo, never()).markStatus(eq("sig-2"), anyString()); // peer left completely untouched
        verify(decisionRepo, times(1)).insert(any());                // only the entering signal's row
    }

    // -------------------------------------------------------------------
    // place-entry: happy path
    // -------------------------------------------------------------------

    @Test
    void placeEntry_happyPath_placesAndBooks() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("brk-1");
        assertThat(output.get("position_id")).isEqualTo(77L);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        assertThat(req.symbol()).isEqualTo("ACME");
        assertThat(req.side()).isEqualTo("BUY");
        // qty is server-side sizer output (tranche 1000 / price 100), not caller-supplied.
        assertThat(req.qty()).isEqualByComparingTo("10");
        // The bracket carries the BUFFERED stop: logical 95.00 - 1 x atrEff 2.
        assertThat(req.stopLossStop()).isEqualByComparingTo("93.00");
        assertThat(req.limitPrice()).isEqualByComparingTo("100");
        // No take-profit leg: the LLM supplied none and Dracul no longer invents one. A synthetic
        // target is exactly what Saxo rejected with TooFarFromEntryOrder, taking the stop with it.
        assertThat(req.takeProfitLimit()).isNull();
        assertThat(req.clientRef()).isEqualTo("sig-1");

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().status()).isEqualTo("OPEN");
        assertThat(posCaptor.getValue().brokerOrderId()).isEqualTo("brk-1");
        assertThat(posCaptor.getValue().stopOrderId()).isEqualTo("stop-1");
        assertThat(posCaptor.getValue().qty()).isEqualByComparingTo("10");
        assertThat(posCaptor.getValue().sector()).isEqualTo("TECH");
        assertThat(posCaptor.getValue().entryDayHigh()).isEqualByComparingTo("101");

        verify(signalRepo).markStatus("sig-1", "ACCEPTED");

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isTrue();
        assertThat(decCaptor.getValue().brokerOrderId()).isEqualTo("brk-1");

        // entryGtdDays=2, FIXED_NOW="2026-07-01T00:00:42Z" is a Wednesday -> +2 days lands on
        // Friday 2026-07-03 (no weekend roll needed).
        verify(positionRepo).setEntryExpiresAt(77L, Instant.parse("2026-07-03T00:00:42Z"));

        verify(executorNotifier).notifyEntryPlaced(any(), any(), any(), any(), any(), any());
    }

    @Test
    void entryInsertPersistsSubmittedLimitPrice() {
        // Book = broker: entry_price is later corrected to the broker's real fill basis by
        // ReconcileService, but submitted_limit_price must keep the original order price
        // forever, so slippage (entry_price - submitted_limit_price) stays computable.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        controller.placeEntry(BEARER, null, body);

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().entryPrice()).isEqualByComparingTo("100");
        assertThat(posCaptor.getValue().submittedLimitPrice()).isEqualByComparingTo("100");
    }

    @Test
    void placeEntry_happyPath_writesRichDecisionLog() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        controller.placeEntry(BEARER, "run-42", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();

        assertThat(log.triggerType()).isEqualTo("SIGNAL");
        assertThat(log.action()).isEqualTo("ENTER");
        assertThat(log.reasonCode()).isNull();
        assertThat(log.runId()).isEqualTo("run-42");
        assertThat(log.signalId()).isEqualTo("sig-1");
        assertThat(log.sourceAgent()).isEqualTo("hunter");
        assertThat(log.sourceAgentVersion()).isEqualTo("v1");
        assertThat(log.symbol()).isEqualTo("ACME");

        JsonNode inputs = log.inputsSnapshot();
        assertThat(inputs).isNotNull();
        for (String key : List.of("signal_confidence", "signal_mechanism", "signal_age_trading_days",
                "order_price", "atr", "book_positions_count", "portfolio_heat_before_pct",
                "portfolio_heat_after_pct", "budget_free", "new_positions_this_week",
                "sector_count_same", "cooldown_status")) {
            assertThat(inputs.has(key)).as("missing key " + key).isTrue();
        }
        assertThat(inputs.path("signal_confidence").asDouble()).isEqualTo(0.9);
        assertThat(inputs.path("signal_mechanism").asString()).isEqualTo("mechanism");
        assertThat(inputs.path("order_price").asDouble()).isEqualTo(100.0);
        assertThat(inputs.path("atr").asDouble()).isEqualTo(2.0);

        JsonNode vetoResults = log.vetoResults();
        assertThat(vetoResults.isArray()).isTrue();
        assertThat(vetoResults.size()).isEqualTo(17);
        for (JsonNode v : vetoResults) {
            assertThat(v.has("check")).isTrue();
            assertThat(v.has("passed")).isTrue();
            assertThat(v.has("measured")).isTrue();
        }

        JsonNode order = log.orderJson();
        assertThat(order).isNotNull();
        assertThat(order.path("type").asString()).isEqualTo("limit_bracket");
        assertThat(order.path("qty").asDouble()).isEqualTo(10.0);
        // limit_price is booked at the resolved orderPrice (ctx.price()=100 fallback here), not
        // the raw (absent) LLM limit_price -- this is the same fix as the BracketRequest below.
        assertThat(order.path("limit_price").asDouble()).isEqualTo(100.0);
        assertThat(order.path("stop_price").asDouble()).isEqualTo(95.0);
        // The LLM supplied no take_profit and none is invented, so the audit row records it as null.
        assertThat(order.path("take_profit").isNull()).isTrue();
        assertThat(order.path("stop_basis").asString()).contains("ATR");
        assertThat(order.path("r_per_share").asDouble()).isEqualTo(5.0);
        assertThat(order.path("position_risk").asDouble()).isEqualTo(50.0);
        assertThat(order.path("gtd_days").asInt()).isEqualTo(2);

        JsonNode latency = log.latency();
        assertThat(latency).isNotNull();
        // signal createdAt "2026-07-01T00:00:00Z", fixedClock at "...T00:00:42Z" -> 42s.
        assertThat(latency.path("signal_to_decision_seconds").asLong()).isEqualTo(42L);
    }

    @Test
    void placeEntry_confidence_landsInDecisionLog_enter() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95,"confidence":0.85}
                """);

        controller.placeEntry(BEARER, "run-42", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();
        assertThat(log.action()).isEqualTo("ENTER");
        assertThat(log.confidenceInDecision()).isEqualTo(0.85);
    }

    @Test
    void placeEntry_confidence_landsInDecisionLog_vetoReject() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.4, new BigDecimal("100")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95,"confidence":0.55}
                """);

        controller.placeEntry(BEARER, "run-7", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();
        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(log.confidenceInDecision()).isEqualTo(0.55);
    }

    @Test
    void placeEntry_noConfidenceArgument_logsNullNeverFabricated() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        controller.placeEntry(BEARER, "run-42", body);

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        assertThat(captor.getValue().confidenceInDecision()).isNull();
    }

    @Test
    void placeEntry_brokerError_noPositionBooked() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenThrow(new BrokerUnavailableException("broker down"));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(positionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus("sig-1", "ACCEPTED");

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("BROKER_ERROR");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("BROKER_ERROR");
        assertThat(log.orderJson()).isNull();
        assertThat(log.inputsSnapshot()).isNotNull();
        assertThat(log.vetoResults().size()).isEqualTo(17);
    }

    @Test
    void placeEntry_brokerError_underCap_leavesPending() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenThrow(new BrokerUnavailableException("agora order rejected: market closed"));
        // Attempt cap = failed RUNS in the window, not rows over all time.
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(1);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(signalRepo, never()).markStatus(eq("sig-1"), eq("REJECTED"));

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("BROKER_ERROR");
    }

    @Test
    void placeEntry_brokerError_atCap_marksRejected() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenThrow(new BrokerUnavailableException("agora order rejected: market closed"));
        // Attempt cap = failed RUNS in the window, not rows over all time.
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(3);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(signalRepo).markStatus("sig-1", "REJECTED");
    }

    @Test
    void placeEntry_threeBrokerErrorsInOneRunDoNotTripTheCap() {
        // The STT regression: three tool calls in ONE night are one failed attempt, not three.
        // Before 2026-07-26 this flipped the signal to REJECTED after the third call.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenThrow(new BrokerUnavailableException("agora order rejected: 429"));
        // The exact STT shape: three BROKER_ERROR rows over the signal's lifetime, but only ONE
        // failed run. A row-counting cap trips here; a run-counting cap must not.
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(3);
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(1);
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-7")).thenReturn(0);
        // Lifetime count > 0 sends the adoption guard to the broker; nothing to adopt.
        when(gateway.orderByRef("depot-1", "sig-1")).thenReturn(Optional.empty());

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-7", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(signalRepo, never()).markStatus(eq("sig-1"), eq("REJECTED"));
    }

    @Test
    void placeEntry_threeFailedRunsInsideTheWindowTripTheCap() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenThrow(new BrokerUnavailableException("agora order rejected: 429"));
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(3);
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-7")).thenReturn(0);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-7", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(signalRepo).markStatus("sig-1", "REJECTED");
    }

    @Test
    void placeEntry_throttleBlocksTheThirdBrokerCallOfTheSameRun() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-7")).thenReturn(2);
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(2);
        when(gateway.orderByRef("depot-1", "sig-1")).thenReturn(Optional.empty());

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-7", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_RETRY_EXHAUSTED");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(eq("sig-1"), eq("REJECTED"));

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("BROKER_RETRY_EXHAUSTED");
    }

    @Test
    void placeEntry_adoptionGuardUsesTheLifetimeCountNotTheWindow() {
        // The duplicate guard must still fire for an error older than the window — otherwise a
        // still-open order from four days ago goes unnoticed and a SECOND order is placed.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(1);
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(0);
        when(gateway.orderByRef("depot-1", "sig-1")).thenReturn(Optional.of(
                new BrokerOrder("brk-existing", "sig-1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                        new BigDecimal("7"), BigDecimal.ZERO, null, null)));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-7", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("brk-existing");

        verify(gateway).orderByRef("depot-1", "sig-1");
        verify(gateway, never()).placeBracket(any(), any());

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, atLeastOnce()).insert(decCaptor.capture());
        assertThat(decCaptor.getAllValues()).anyMatch(d -> "DUPLICATE".equals(d.rejectReason()));
    }

    @Test
    void placeEntry_anAdoptableOrderIsTakenEvenWhenTheRunBudgetIsExhausted() {
        // Ordering invariant: adoption runs BEFORE the throttle. Reversed, an order that is
        // already live at the broker would be left without a DB counterpart forever.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(2);
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-7")).thenReturn(2);
        when(gateway.orderByRef("depot-1", "sig-1")).thenReturn(Optional.of(
                new BrokerOrder("brk-existing", "sig-1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                        new BigDecimal("7"), BigDecimal.ZERO, null, null)));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-7", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("reason")).isNotEqualTo("BROKER_RETRY_EXHAUSTED");
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("brk-existing");

        verify(gateway, never()).placeBracket(any(), any());

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, atLeastOnce()).insert(decCaptor.capture());
        assertThat(decCaptor.getAllValues()).anyMatch(d -> "DUPLICATE".equals(d.rejectReason()));
        assertThat(decCaptor.getAllValues())
                .noneMatch(d -> "BROKER_RETRY_EXHAUSTED".equals(d.rejectReason()));
    }

    @Test
    void placeEntry_dbFailureAfterPlacedBracket_escalatesOrphanedOrder() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("bracket-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenThrow(new RuntimeException("db down"));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("ORPHANED_ORDER");
        assertThat(output.get("broker_order_id")).isEqualTo("bracket-1");

        verify(telegram).notifyAlert(eq("ACME"), eq("ORPHANED_ORDER"), eq("CRITICAL"), contains("bracket-1"));
        verify(signalRepo, never()).markStatus("sig-1", "ACCEPTED");

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("ORPHANED_ORDER");
        assertThat(decCaptor.getValue().brokerOrderId()).isEqualTo("bracket-1");
    }

    @Test
    void placeEntry_acceptedAuditInsertFails_stillReportsPlacedTrue() {
        // Position insert + markStatus(ACCEPTED) succeed durably; only the accepted-audit
        // decisionRepo.insert throws. The response must NOT flip into a false ORPHANED_ORDER
        // -- that would contradict persisted state.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("bracket-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);
        doThrow(new RuntimeException("audit db down")).when(decisionRepo)
                .insert(argThat(d -> d != null && d.accepted()));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("bracket-1");

        verify(signalRepo).markStatus("sig-1", "ACCEPTED");
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------
    // place-entry: idempotent retry after a prior BROKER_ERROR — adopt the existing
    // broker order via clientRef instead of placing a second one.
    // -------------------------------------------------------------------

    @Test
    void placeEntry_retryWithExistingBrokerOrder_adoptsNotReplaces() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(1);
        when(gateway.orderByRef("depot-1", "sig-1")).thenReturn(Optional.of(
                new BrokerOrder("brk-existing", "sig-1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                        new BigDecimal("7"), BigDecimal.ZERO, null, null)));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("brk-existing");

        verify(gateway, never()).placeBracket(any(), any());

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().brokerOrderId()).isEqualTo("brk-existing");
        assertThat(posCaptor.getValue().qty()).isEqualByComparingTo(new BigDecimal("7"));

        verify(signalRepo).markStatus("sig-1", "ACCEPTED");
    }

    @Test
    void placeEntry_retryWithTerminalBrokerOrder_replaces() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(1);
        when(gateway.orderByRef("depot-1", "sig-1")).thenReturn(Optional.of(
                new BrokerOrder("brk-cancelled", "sig-1", "ACME", OrderRole.ENTRY, OrderStatus.CANCELLED,
                        new BigDecimal("10"), BigDecimal.ZERO, null, null)));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-fresh", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(78L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("brk-fresh");

        verify(gateway).placeBracket(eq("depot-1"), any(BracketRequest.class));

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().brokerOrderId()).isEqualTo("brk-fresh");

        verify(signalRepo).markStatus("sig-1", "ACCEPTED");
    }

    @Test
    void placeEntry_firstAttempt_doesNotCallOrderByRef() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(0);
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("brk-1");

        verify(gateway, never()).orderByRef(any(), any());
        verify(gateway, times(1)).placeBracket(eq("depot-1"), any(BracketRequest.class));
    }

    // -------------------------------------------------------------------
    // place-entry: order-price basis (limit price or fresh close) drives sizing,
    // guard, and booking -- never the stale signal reference
    // -------------------------------------------------------------------

    @Test
    void placeEntry_divergentPrices_usesFreshPriceBasis() {
        // Stale signal.referencePrice=105 vs fresh ctx.price()=100 (happyContext: atr=2, no
        // swingLow -> SELL stop window [105, 106.5], since price fell after the signal's reference
        // was captured). stop=106 sits inside that fresh window and is > orderPrice(100), so the
        // fresh-basis guard passes. The OLD stale-reference guard would have wrongly rejected this
        // same order: 106 is not > referencePrice(105) by enough margin for some legacy checks.
        // CHASED_AWAY (signal.direction() is "LONG" from the shared signal() helper, i.e. only
        // fires when price rises away from the reference) never trips on a falling price
        // (price(100) <= referencePrice(105) + atr(2) trivially holds). BELOW_ANCHOR's LONG-side
        // band (value mechanism -> 3xATR=6) also passes: adverse 105-100=5 <= 6.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("105")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"SELL","stop_price":106}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        // qty basis is the fresh ctx.price()=100, not the stale reference=110: floor(1000/100)=10
        assertThat(reqCaptor.getValue().qty()).isEqualByComparingTo("10");

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().entryPrice()).isEqualByComparingTo("100");
        assertThat(posCaptor.getValue().highestPrice()).isEqualByComparingTo("100");
    }

    @Test
    void placeEntry_limitPriceWinsAsBasis() {
        // An LLM-supplied limit_price=99 must win over ctx.price()=100 as the single order-price
        // basis for sizing and booking. BracketRequest.limitPrice carries the tick-rounded order
        // price (orderPriceRounded), not the LLM's raw argument -- here they happen to coincide
        // because 99 already sits on the 0.01 grid.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":99,"stop_price":93}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        // qty basis is limit_price=99, not ctx.price()=100: floor(1000/99)=10
        assertThat(reqCaptor.getValue().qty()).isEqualByComparingTo("10");
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("99");

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        assertThat(posCaptor.getValue().entryPrice()).isEqualByComparingTo("99");
        assertThat(posCaptor.getValue().highestPrice()).isEqualByComparingTo("99");
    }

    // -------------------------------------------------------------------
    // place-entry: no synthetic take-profit leg (Agora accepts entry + stop only)
    // -------------------------------------------------------------------

    @Test
    void placeEntry_noTakeProfit_placesWithoutATargetLeg_buy() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // BUY, no take_profit supplied → the bracket goes out as entry + stop, no target invented.
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNull();
    }

    @Test
    void placeEntry_noTakeProfit_placesWithoutATargetLeg_sell() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // SELL side too: no target is invented in either direction.
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"SELL","stop_price":105}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNull();
    }

    @Test
    void placeEntry_withoutTakeProfitTheBracketStillCarriesItsStop() {
        // The whole point of dropping the synthetic target: a rejected target used to take the
        // STOP leg down with it. Entry + stop must survive on their own.
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", null, "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        assertThat(req.takeProfitLimit()).isNull();
        // The protective leg is untouched.
        assertThat(req.stopLossStop()).isEqualByComparingTo("93.00");
    }

    @Test
    void placeEntry_orderJsonRecordsTheAbsentTarget() {
        // The audit row must state truthfully what was placed: the key stays, the value is null.
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", null, "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.has("take_profit")).isTrue();
        assertThat(order.path("take_profit").isNull()).isTrue();
    }

    @Test
    void placeEntry_explicitTakeProfit_usedUnchanged() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // LLM supplies take_profit=108 → must be used as-is, not overwritten by the 3R default (115)
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95,"take_profit":108}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isEqualByComparingTo("108");
    }

    // -------------------------------------------------------------------
    // place-entry: tick-grid rounding (StopWindowRounding + orderPriceRounded split)
    //
    // Table this section pins: veto/log inputs get the RAW orderPrice; everything that reaches
    // the broker or the book (BracketRequest, ExecutorPosition, order_json) gets
    // orderPriceRounded / the StopWindowRounding-derived stop. See ExecutorWebhookController
    // :529-598 and StopWindowRounding.
    // -------------------------------------------------------------------

    @Test
    void placeEntry_belowAnchorRegressionGate_rawPriceFedToVeto_roundedPriceInBracket() {
        // Reproduces the HAS/KALU/GSHD production loss: BELOW_ANCHOR compares the effective entry
        // price against referencePrice with a ZERO-width band for drift-anchor mechanisms (PEAD),
        // i.e. it demands eff >= referencePrice exactly. referencePrice == limit_price == 96.415
        // (not on the 0.01 grid). If orderPrice were rounded DOWN to 96.41 before reaching the
        // veto (the bug this task closes), eff(96.41) < referencePrice(96.415) and BELOW_ANCHOR
        // would wrongly reject a signal that is exactly at its anchor. The veto must see the RAW
        // 96.415; only the broker-bound BracketRequest may see the rounded 96.41.
        ExecutorSignal sig = signal("sig-1", 0.9, new BigDecimal("96.415"), "PENDING", "PEAD");
        when(signalRepo.findById("sig-1")).thenReturn(sig);
        // Market price close enough to the reference that CHASED_AWAY (chaseAtrMult=1.0xATR=2)
        // does not also fire and mask which veto this test is actually pinning.
        when(assembler.assemble(any())).thenReturn(withPrice(happyContext(), new BigDecimal("97")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":96.415}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);
        verify(decisionRepo, never()).insert(argThat(d -> "BELOW_ANCHOR".equals(d.rejectReason())));

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("96.41");
    }

    @Test
    void placeEntry_rawOrderPriceInAuditSnapshot_neverRounded() {
        // BELOW_ANCHOR and PATTERN_GATE (VetoService.java:359, :266) both read the same
        // `orderPrice` argument passed into vetoService.evaluate(...) — there is exactly one
        // raw-price channel into the whole veto catalog, and this pins that it is never
        // reassigned to the rounded value. The decision_log audit snapshot is fed from the
        // identical variable (:578-ish `logEntryDecision(..., orderPrice, ...)`), so asserting it
        // here is equivalent to asserting it for every veto that reads orderPrice, not just
        // BELOW_ANCHOR.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100.017,"stop_price":94}
                """);

        controller.placeEntry(BEARER, "run-audit", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().inputsSnapshot().path("order_price").asDouble())
                .isEqualTo(100.017);

        // Meanwhile the broker/book-bound price is rounded.
        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("100.01");
    }

    // -------------------------------------------------------------------
    // place-entry: StopWindowRounding rule 2 — the controller call site is the only place left
    // that can still mix prices. StopWindowRounding.compute takes a single price by construction
    // and cannot regress to this; ExecutorWebhookController:~560 chooses which price to hand it,
    // and nothing below the controller can see that choice. These two tests are the actual
    // regression guard for rule 2 (see the corrected comment on
    // mixingRawAndRoundedPricesIsRejectedToday_documentation in StopWindowRoundingTest).
    // -------------------------------------------------------------------

    @Test
    void placeEntry_stopWindowRule2Regression_buy() {
        // BUY, limit_price 100.017, atr 2.006, stop_price 99. Hand-computed both ways:
        //   correct (window from orderPriceRounded=100.01): anchor=94.995 -> stopMax rounds
        //     FLOOR to 94.99; proposal 99 clamps down to 94.99.
        //   mutated (window from raw orderPrice=100.017):    anchor=95.002 -> stopMax rounds
        //     FLOOR to 95.00; proposal 99 clamps down to 95.00.
        // The two anchors straddle the 95.00 tick boundary (94.995 < 95.00 < 95.002), which is
        // exactly why this fixture discriminates: a same-side rounding delta smaller than one
        // tick usually lands in the same bucket either way and proves nothing.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100.017")));
        when(assembler.assemble(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("100.017"), new BigDecimal("2.006")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100.017,"stop_price":99}
                """);

        stopRoundingController().placeEntry(BEARER, null, body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("94.99");
    }

    @Test
    void placeEntry_stopWindowRule2Regression_sell() {
        // SELL mirror. limit_price 100.013, atr 2.006, stop_price 90.
        //   correct (window from orderPriceRounded=100.02): anchor=105.035 -> stopMin rounds
        //     CEILING to 105.04.
        //   mutated (window from raw orderPrice=100.013):    anchor=105.028 -> stopMin rounds
        //     CEILING to 105.03.
        // Same straddle construction as the BUY case, mirrored: 105.028 < 105.03 < 105.035 < 105.04.
        ExecutorSignal sig = new ExecutorSignal("sig-1", "hunter", "v1", "ACME", "SELL",
                0.9, "mechanism", List.of("X"), "3m", new BigDecimal("100.013"), "PENDING",
                "2026-07-01T00:00:00Z");
        when(signalRepo.findById("sig-1")).thenReturn(sig);
        when(assembler.assemble(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("100.013"), new BigDecimal("2.006")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"SELL","limit_price":100.013,"stop_price":90}
                """);

        stopRoundingController().placeEntry(BEARER, null, body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("105.04");
    }

    @Test
    void placeEntry_subCentPrices_reachTheBrokerOnTick() {
        // The three literal production prices that triggered BROKER_ERROR
        // (PriceNotInTickSizeIncrements) before tick rounding existed — verbatim from
        // decision_log, also pinned in TickSizeTest.java:17-22 (BUY/SELL both directions, since
        // the real losses spanned both). No stop_price is supplied, so the final stop is the
        // clamp-to-stopMin default computed by hand below against happyContext's ATR=2 window —
        // exact values, not just an on-tick check: remainder(0.01)==0 alone is satisfied by
        // EITHER rounding direction and would not catch a direction bug (e.g. CEILING where FLOOR
        // is correct) at the controller level, which is exactly the class of defect this task
        // exists to close.
        record Fixture(String signalId, String side, BigDecimal limitPrice,
                BigDecimal wantEntry, BigDecimal wantStop) {}
        List<Fixture> fixtures = List.of(
                // BUY floors the entry; the resulting window's tight stop bound is the anchor
                // (price - 2.5*ATR), the wide bound is (price - 3*ATR - 0.25*ATR); a null proposal
                // clamps to the wide bound (stopMin).
                new Fixture("has-1", "BUY", new BigDecimal("96.415"),
                        new BigDecimal("96.41"), new BigDecimal("89.91")),
                new Fixture("kalu-1", "BUY", new BigDecimal("151.345"),
                        new BigDecimal("151.34"), new BigDecimal("144.84")),
                new Fixture("gshd-1", "BUY", new BigDecimal("70.505"),
                        new BigDecimal("70.50"), new BigDecimal("64.00")),
                // SELL ceilings the entry; mirror windows, tight bound (stopMin) is the anchor.
                new Fixture("has-2", "SELL", new BigDecimal("96.415"),
                        new BigDecimal("96.42"), new BigDecimal("101.42")),
                new Fixture("kalu-2", "SELL", new BigDecimal("151.345"),
                        new BigDecimal("151.35"), new BigDecimal("156.35")),
                new Fixture("gshd-2", "SELL", new BigDecimal("70.505"),
                        new BigDecimal("70.51"), new BigDecimal("75.51")));

        for (Fixture f : fixtures) {
            reset(gateway, positionRepo);
            ExecutorSignal sig = "SELL".equals(f.side())
                    ? new ExecutorSignal(f.signalId(), "hunter", "v1", "ACME", "SELL",
                            0.9, "mechanism", List.of("X"), "3m", f.limitPrice(), "PENDING",
                            "2026-07-01T00:00:00Z")
                    : signal(f.signalId(), 0.9, f.limitPrice());
            when(signalRepo.findById(f.signalId())).thenReturn(sig);
            // Market price pinned to the fixture's own limit_price (drift 0) so CHASED_AWAY /
            // BELOW_ANCHOR never fire for an unrelated reason -- this test is about on-tick
            // rounding direction, not veto thresholds. happyContext's default ATR=2 is used
            // unchanged for the hand-computed window above.
            when(assembler.assemble(any())).thenReturn(withPrice(happyContext(), f.limitPrice()));
            when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                    .thenReturn(new PlacedBracket("brk-" + f.signalId(), "stop-" + f.signalId(),
                            null, f.signalId(), OrderStatus.WORKING));
            when(positionRepo.insert(any())).thenReturn(1L);

            JsonNode body = json(String.format(java.util.Locale.ROOT,
                    "{\"signal_id\":\"%s\",\"symbol\":\"ACME\",\"side\":\"%s\",\"limit_price\":%s}",
                    f.signalId(), f.side(), f.limitPrice()));

            stopRoundingController().placeEntry(BEARER, null, body);

            ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
            verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
            BracketRequest req = reqCaptor.getValue();
            assertThat(req.limitPrice()).as(f.signalId() + " limit_price")
                    .isEqualByComparingTo(f.wantEntry());
            assertThat(req.stopLossStop()).as(f.signalId() + " stop_price")
                    .isEqualByComparingTo(f.wantStop());
        }
    }

    @Test
    void placeEntry_dbBrokerAndAuditPriceFieldsAgree_allFiveFields() {
        // DB <-> broker <-> order_json must be byte-identical (via compareTo) across all five
        // tick-rounded price fields, using a fixture where BOTH the entry and the stop are
        // sub-cent so rounding actually moves both.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100.017,"stop_price":94.005}
                """);

        stopRoundingController().placeEntry(BEARER, "run-5fields", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        assertThat(req.limitPrice()).isEqualByComparingTo("100.01");
        assertThat(req.stopLossStop()).isEqualByComparingTo("94.01");

        ArgumentCaptor<ExecutorPosition> posCaptor = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(posCaptor.capture());
        ExecutorPosition pos = posCaptor.getValue();
        assertThat(pos.entryPrice()).usingComparator(BigDecimal::compareTo).isEqualTo(req.limitPrice());
        assertThat(pos.initialStop()).usingComparator(BigDecimal::compareTo).isEqualTo(req.stopLossStop());
        assertThat(pos.activeStop()).usingComparator(BigDecimal::compareTo).isEqualTo(req.stopLossStop());
        assertThat(pos.highestPrice()).usingComparator(BigDecimal::compareTo).isEqualTo(req.limitPrice());
        assertThat(pos.submittedLimitPrice()).usingComparator(BigDecimal::compareTo).isEqualTo(req.limitPrice());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(new BigDecimal(order.path("limit_price").asString()))
                .usingComparator(BigDecimal::compareTo).isEqualTo(req.limitPrice());
        assertThat(new BigDecimal(order.path("stop_price").asString()))
                .usingComparator(BigDecimal::compareTo).isEqualTo(req.stopLossStop());
    }

    @Test
    void placeEntry_sizingUsesTheRoundedPrice_notionalStaysWithinTranche() {
        // trancheAmount=10000, limit_price=100.005 (rounds DOWN to 100.00 for BUY) -> sizing off
        // the SAME rounded price the broker will actually fill at: qty=floor(10000/100.00)=100,
        // notional=100*100.00=10000.00, exactly at (never over) the tranche budget. Sizing off the
        // raw 100.005 would floor to qty=99, understating what the sizer itself will size once
        // rule 2 (window/size share the rounded price) is honoured everywhere.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any())).thenReturn(withTrancheAmountAndAdv(happyContext(), new BigDecimal("10000")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100.005,"stop_price":95}
                """);

        controllerWith(BUFFER_ONE, sizer, tranche2Detector, RISK_UNBOUND, 0.06)
                .placeEntry(BEARER, null, body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        assertThat(req.limitPrice()).isEqualByComparingTo("100.00");
        assertThat(req.qty()).isEqualByComparingTo("100");
        BigDecimal notional = req.qty().multiply(req.limitPrice());
        assertThat(notional).isEqualByComparingTo("10000.00");
        assertThat(notional.compareTo(new BigDecimal("10000"))).isLessThanOrEqualTo(0);
    }

    @Test
    void placeEntry_serverWindowNull_stopStillTickRounded() {
        // Even with a defensive null window (real PositionSizer never returns one; only a mock
        // simulates a broken server window), StopWindowRounding still tick-rounds the proposed
        // stop on its own -- there is just no bound to clamp it into.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        PositionSizer brokenSizer = mock(PositionSizer.class);
        when(brokenSizer.stopWindow(any(), any(), any(), any()))
                .thenReturn(new StopWindow(null, null, "broken"));
        when(brokenSizer.size(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Sizing(new BigDecimal("10"), new BigDecimal("6"),
                        new BigDecimal("60"), null, null, true, "broken",
                        new BigDecimal("10"), new BigDecimal("10"), "NOTIONAL", null));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        // 94.005 is not on the 0.01 grid; BUY rounds a stop CEILING -> 94.01.
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":94.005}
                """);

        ResponseEntity<?> resp = controllerWith(BigDecimal.ZERO, brokenSizer, tranche2Detector)
                .placeEntry(BEARER, null, body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);
        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("94.01");
    }

    @Test
    void placeEntry_missingData_takeProfitPresentInBody_noNpe() {
        // ctx.missing() non-empty -> orderPrice/orderPriceRounded stay null and the takeProfit
        // rounding block (guarded by the same branch) must never execute -- touching it in the
        // else-branch would NPE on the null orderPriceRounded. This is the concrete regression a
        // careless placement of the take-profit-rounding code would trip.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any())).thenReturn(unavailableContext());

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95,"take_profit":108}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("DATA_UNAVAILABLE");
        verifyNoInteractions(gateway);
    }

    @Test
    void placeEntry_takeProfitCollapsesOntoRoundedEntry_becomesNull_bracketStillPlaced() {
        // BUY entry 96.41 (already on-tick), take_profit 96.418 -> roundTarget floors it to 96.41,
        // exactly the entry: not a valid target for a long (must be strictly above entry). The
        // rounded target is dropped (null) rather than sent as a degenerate/rejected leg; the
        // bracket still goes out with its stop.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("96.41")));
        when(assembler.assemble(any())).thenReturn(withPrice(happyContext(), new BigDecimal("96.41")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", null, "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":96.41,"stop_price":90,"take_profit":96.418}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-tp-collapse", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNull();
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("96.41");

        // The drop must leave an audit trace: order_json.take_profit alone (null) is
        // indistinguishable from "the LLM never sent one" -- proposed_take_profit + the dropped
        // flag are what let decision_log explain why a signal's TP vanished.
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("take_profit").isNull()).isTrue();
        assertThat(order.path("proposed_take_profit").asDouble()).isEqualTo(96.418);
        assertThat(order.path("take_profit_dropped").asBoolean()).isTrue();
    }

    @Test
    void placeEntry_takeProfitNotDropped_dropFlagFalse_proposedEqualsFinal() {
        // Mirror of the collapse test: an ordinary, non-colliding take_profit is neither dropped
        // nor mis-flagged.
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        JsonNode body = json("""
                {"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95,"take_profit":108}
                """);

        controller.placeEntry(BEARER, "run-tp-kept", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("take_profit").asDouble()).isEqualTo(108.0);
        assertThat(order.path("proposed_take_profit").asDouble()).isEqualTo(108.0);
        assertThat(order.path("take_profit_dropped").asBoolean()).isFalse();
    }

    @Test
    void placeEntry_orderJsonRecordsBothRawAndRoundedStopBounds() {
        // order_json must carry BOTH the raw risk-layer window (stop_min/stop_max, unchanged
        // audit continuity) AND the rounded bounds OrderGuard actually enforced
        // (stop_min_rounded/stop_max_rounded) -- without the second pair the audit trail cannot
        // show why a stop next to a bound was accepted or rejected.
        // happyContext: price=100, atr=2, no swingLow -> raw BUY window [93.5, 95]; both bounds
        // are already on-tick here, so raw == rounded for this fixture -- the point is that both
        // keys are present and correct, not that they differ.
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        JsonNode body = json("""
                {"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":94}
                """);

        controller.placeEntry(BEARER, "run-bounds", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.hasNonNull("stop_min")).isTrue();
        assertThat(order.hasNonNull("stop_max")).isTrue();
        assertThat(order.hasNonNull("stop_min_rounded")).isTrue();
        assertThat(order.hasNonNull("stop_max_rounded")).isTrue();
        assertThat(order.path("stop_min").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(95.0);
        assertThat(order.path("stop_min_rounded").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max_rounded").asDouble()).isEqualTo(95.0);
    }

    @Test
    void placeEntry_orderJsonRecordsRoundedStopBounds_offTick() {
        // Off-tick variant of the test above: happyContext's on-tick fixture (price=100, atr=2)
        // produces raw == rounded bounds, so a mutation that logs the RAW window
        // (window.stopMin()/stopMax()) into stop_min_rounded/stop_max_rounded instead of the
        // actually-rounded pair would still pass there. price=100.017, atr=2.006 (the same
        // fixture used by placeEntry_stopWindowRule2Regression_buy) straddles a tick boundary on
        // both bounds, so raw and rounded genuinely differ here:
        //   orderPriceRounded = floor(100.017, 2) = 100.01
        //   raw:     stop_max (anchor) = 100.01 - 2.5*2.006  = 94.9950
        //            stop_min (floor)  = 100.01 - 3.25*2.006 = 93.4905
        //   rounded: stop_max_rounded  = FLOOR(94.9950, 2)   = 94.99
        //            stop_min_rounded  = CEILING(93.4905, 2) = 93.50
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100.017")));
        when(assembler.assemble(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("100.017"), new BigDecimal("2.006")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        JsonNode body = json("""
                {"signal_id":"s1","symbol":"ACME","side":"BUY","limit_price":100.017,"stop_price":94}
                """);

        controller.placeEntry(BEARER, "run-bounds-off-tick", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.hasNonNull("stop_min")).isTrue();
        assertThat(order.hasNonNull("stop_max")).isTrue();
        assertThat(order.hasNonNull("stop_min_rounded")).isTrue();
        assertThat(order.hasNonNull("stop_max_rounded")).isTrue();
        assertThat(order.path("stop_min").asDouble()).isEqualTo(93.4905);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(94.995);
        assertThat(order.path("stop_min_rounded").asDouble()).isEqualTo(93.50);
        assertThat(order.path("stop_max_rounded").asDouble()).isEqualTo(94.99);
    }

    @Test
    void placeEntry_degenerateWindow_inWindowProposal_stopClampedFalse() {
        // Controller-level pin of the StopWindowRounding fix: in the degenerate branch, a
        // proposal already inside the RAW window must report stop_clamped=false -- comparing
        // against a tick-rounded value that this branch never computes (the old bug) would
        // wrongly report true.
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("1.50")));
        EntryContext base = withPriceAndAtr(happyContext(), new BigDecimal("1.50"), new BigDecimal("0.03"));
        EntryContext withSwingLow = new EntryContext(base.account(), base.price(), base.atr(),
                new BigDecimal("1.399"), base.adv20Notional(), base.dayHigh(), base.candidateSector(),
                base.openPositions(), base.activeCooldowns(), base.pendingSignals(), base.entriesThisWeek(),
                base.signalAgeTradingDays(), base.trancheAmount(), base.totalBudget(), base.openExposure(),
                base.openHeat(), base.openMechanisms(), base.fxToAccount(), base.missing(), base.quoteCurrency(), base.atrShort(), base.atrEff(), base.openExposureByMechanism());
        when(assembler.assemble(any())).thenReturn(withSwingLow);
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // Raw window here is [1.3915, 1.399] (degenerate once rounded inward); 1.395 sits inside
        // it, so nothing should actually get clamped. minPrice lowered to 1 -- the empirically-
        // verified degenerate fixture is a sub-$5 price and LIQUIDITY would otherwise reject it
        // for an unrelated reason.
        JsonNode body = json("""
                {"signal_id":"s1","symbol":"ACME","side":"BUY","limit_price":1.50,"stop_price":1.395}
                """);

        controllerWithMinPrice(new BigDecimal("1")).placeEntry(BEARER, "run-degenerate", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("stop_clamped").asBoolean()).isFalse();
        assertThat(order.path("stop_price").asDouble()).isEqualTo(1.395);
    }

    @Test
    void placeEntry_proposedStopAuditsRawValue_stopClampedFalseWhenOnlyRounded() {
        // proposed_stop must carry the raw LLM value verbatim (never rounded/clamped), and
        // stop_clamped must be FALSE when the final stop differs from the proposal only because
        // it was tick-rounded, not because a window bound actually bound it.
        // happyContext: price=100, atr=2, no swingLow -> BUY window [93.5, 95]. A proposal of 94.6
        // is inside that window; TickSize.roundStop("BUY", 94.6) = CEILING = 94.6 already on-tick
        // -> no visible change at all here, so pick a genuinely sub-cent in-window proposal.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        // 94.006 is inside [93.5, 95] and rounds (CEILING) to 94.01, still comfortably inside the
        // window -- so the final stop differs from the proposal by rounding only, not clamping.
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":94.006}
                """);

        stopRoundingController().placeEntry(BEARER, "run-proposed-stop", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("94.01");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("proposed_stop").asDouble()).isEqualTo(94.006);
        assertThat(order.path("stop_clamped").asBoolean()).isFalse();
    }

    @Test
    void placeEntry_stopActuallyClampedByWindow_stopClampedTrue() {
        // Mirror of the test above: a proposal outside the window is genuinely clamped, and
        // stop_clamped must stay TRUE (unchanged behavior, not weakened by the new rounding step).
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":98.5}
                """);

        stopRoundingController().placeEntry(BEARER, "run-clamped", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("95");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode order = logCaptor.getValue().orderJson();
        assertThat(order.path("proposed_stop").asDouble()).isEqualTo(98.5);
        assertThat(order.path("stop_clamped").asBoolean()).isTrue();
    }

    // -------------------------------------------------------------------
    // place-entry: Vistierie envelope ({"run_id","tool_name","input":{...}})
    // -------------------------------------------------------------------

    @Test
    void placeEntry_vistierieEnvelope_placesIdenticallyToTopLevel() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-9", "stop-9", null, "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(42L);

        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95}}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "r1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("broker_order_id")).isEqualTo("brk-9");
        assertThat(output.get("position_id")).isEqualTo(42L);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        assertThat(req.side()).isEqualTo("BUY");
        assertThat(req.qty()).isEqualByComparingTo("10");
        assertThat(req.stopLossStop()).isEqualByComparingTo("93.00");
        assertThat(req.clientRef()).isEqualTo("s1");

        verify(signalRepo).markStatus("s1", "ACCEPTED");
    }

    @Test
    void placeEntry_vistierieEnvelope_unknownSignalStillRejects() {
        when(signalRepo.findById("ghost")).thenReturn(null);

        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"ghost","symbol":"ZZZ","side":"BUY","stop_price":95}}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "r1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");
        verify(gateway, never()).placeBracket(any(), any());
    }

    // -------------------------------------------------------------------
    // fetch-pending-signals
    // -------------------------------------------------------------------

    @Test
    void fetchPending_serializesShape() {
        when(signalRepo.findPending(50)).thenReturn(List.of(signal("sig-1", 0.8, new BigDecimal("100"))));

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        Map<String, Object> output = outputOf(resp);
        List<?> signals = (List<?>) output.get("signals");
        assertThat(signals).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) signals.get(0);
        assertThat(first.get("symbol")).isEqualTo("ACME");
        assertThat(first.get("confidence")).isEqualTo(0.8);
        assertThat(first.get("kill_criteria")).isEqualTo(List.of("X"));
    }

    @Test
    void fetchPending_enrichesWithIndicatorLevelsWhenAvailable() {
        when(signalRepo.findPending(50)).thenReturn(List.of(signal("sig-1", 0.8, new BigDecimal("100"))));
        when(executorIndicators.levels("ACME", 22, 20)).thenReturn(
                new ExecutorIndicators.Levels(true, new BigDecimal("2.5"), new BigDecimal("92"), new BigDecimal("100"), null));

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) outputOf(resp).get("signals")).get(0);
        assertThat(first.get("atr")).isEqualTo(new BigDecimal("2.5"));
        assertThat(first.get("swing_low")).isEqualTo(new BigDecimal("92"));
        assertThat(first.get("reference_price")).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void fetchPending_enrichesWithStopWindowWhenLevelsAvailable() {
        // direction is "BUY" (never "LONG" in prod) so the sizer's stopWindow takes the correct
        // below-price BUY branch instead of tautologically re-deriving the same (possibly wrong)
        // branch the controller used.
        ExecutorSignal sig = new ExecutorSignal("sig-1", "hunter", "v1", "ACME", "BUY",
                0.8, "mechanism", List.of("X"), "3m", new BigDecimal("100"), "PENDING",
                "2026-07-01T00:00:00Z");
        when(signalRepo.findPending(50)).thenReturn(List.of(sig));
        when(executorIndicators.levels("ACME", 22, 20)).thenReturn(
                new ExecutorIndicators.Levels(true, new BigDecimal("2.5"), new BigDecimal("92"), new BigDecimal("100"), null));

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) outputOf(resp).get("signals")).get(0);
        // price=100, atr=2.5, swingLow=92 -> BUY anchor=min(100-6.25,92)=92, floor=min(100-7.5,92)-0.625=91.375.
        BigDecimal stopMin = (BigDecimal) first.get("stop_min");
        BigDecimal stopMax = (BigDecimal) first.get("stop_max");
        assertThat(stopMin).isEqualByComparingTo("91.375");
        assertThat(stopMax).isEqualByComparingTo("92");
        assertThat(stopMin.compareTo(stopMax)).isLessThanOrEqualTo(0);
        assertThat(stopMin).isLessThan(new BigDecimal("100"));
        assertThat(stopMax).isLessThan(new BigDecimal("100"));
    }

    @Test
    void fetchPending_ranksByMechanismDiversityThenConfidence() {
        ExecutorSignal heldHigh = signal("held-high", 0.95, new BigDecimal("100"), "PENDING", "PEAD");
        ExecutorSignal newLow = signal("new-low", 0.30, new BigDecimal("100"), "PENDING", "MERGER_ARB");
        ExecutorSignal newHigh = signal("new-high", 0.90, new BigDecimal("100"), "PENDING", "SPINOFF");
        when(signalRepo.findPending(50)).thenReturn(List.of(heldHigh, newLow, newHigh));

        // openPosition() hardcodes sourceSignalId="sig-1", so stub findById for that id.
        ExecutorSignal heldSource = signal("sig-1", 0.9, new BigDecimal("100"), "ACCEPTED", "PEAD");
        when(signalRepo.findById("sig-1")).thenReturn(heldSource);
        when(positionRepo.findOpen()).thenReturn(
                List.of(openPosition(1, "HELD", "BUY", new BigDecimal("100"), new BigDecimal("95"))));

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) (List<?>) outputOf(resp).get("signals");
        assertThat(signals).extracting(s -> s.get("signal_id"))
                .containsExactly("new-high", "new-low", "held-high");
    }

    @Test
    void fetchPending_indicatorsUnavailable_fieldsNullWithoutError() {
        when(signalRepo.findPending(50)).thenReturn(List.of(signal("sig-1", 0.8, new BigDecimal("100"))));
        when(executorIndicators.levels("ACME", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());

        ResponseEntity<?> resp = controller.fetchPendingSignals(BEARER, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) outputOf(resp).get("signals")).get(0);
        assertThat(first.get("atr")).isNull();
        assertThat(first.get("swing_low")).isNull();
        assertThat(first.get("stop_min")).isNull();
        assertThat(first.get("stop_max")).isNull();
    }

    // -------------------------------------------------------------------
    // get-account
    // -------------------------------------------------------------------

    @Test
    void getAccount_unavailableEnvelope() {
        when(gateway.account("depot-1")).thenThrow(new BrokerUnavailableException("no session"));

        ResponseEntity<?> resp = controller.getAccount(BEARER, json("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("available")).isEqualTo(false);
        assertThat(output.get("error")).isEqualTo("no session");
    }

    @Test
    void getAccount_happy() {
        when(gateway.account("depot-1")).thenReturn(
                new AccountSnapshot(new BigDecimal("1000"), new BigDecimal("1000"), "USD"));

        ResponseEntity<?> resp = controller.getAccount(BEARER, json("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("cash")).isEqualTo(new BigDecimal("1000"));
        assertThat(output.get("currency")).isEqualTo("USD");
    }

    // -------------------------------------------------------------------
    // submit-decision
    // -------------------------------------------------------------------

    @Test
    void submitDecision_recordsSkips() {
        JsonNode body = json("""
                {
                  "decisions": [
                    {"signal_id":"sig-1","symbol":"ACME","action":"SKIP","rationale":"thin"},
                    {"signal_id":"sig-2","symbol":"BBB","action":"ENTER","rationale":"x"}
                  ]
                }
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(1);

        verify(decisionRepo, times(1)).insert(any());
        verify(signalRepo).markStatus("sig-1", "SKIPPED");
        verify(signalRepo, never()).markStatus(eq("sig-2"), any());
    }

    @Test
    void submitDecision_vistierieEnvelope_recordsSkips() {
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"submit_decision",
                 "input":{"decisions":[
                    {"signal_id":"sig-1","symbol":"ACME","action":"SKIP","rationale":"thin"},
                    {"signal_id":"sig-2","symbol":"BBB","action":"ENTER","rationale":"x"}
                 ]}}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(1);

        verify(decisionRepo, times(1)).insert(any());
        verify(signalRepo).markStatus("sig-1", "SKIPPED");
        verify(signalRepo, never()).markStatus(eq("sig-2"), any());
    }

    /**
     * D3 — HOLD records are part of the contract the prompt asks for but were never persisted.
     * They must land in {@code executor_decision}.
     *
     * <p>The signal status must NOT move: HOLD refers to an OPEN position whose source signal is
     * long {@code ACCEPTED}. Marking it SKIPPED/REJECTED would overwrite the entry verdict and
     * {@code processed_at} with a maintenance-time observation.
     */
    @Test
    void submitDecision_persistsHold_withoutTouchingSignalStatus() {
        JsonNode body = json("""
                {
                  "decisions": [
                    {"signal_id":"sig-1","symbol":"ACME","action":"SKIP","rationale":"thin"},
                    {"signal_id":"sig-2","symbol":"BBB","action":"HOLD","rationale":"not yet"}
                  ]
                }
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", body);

        assertThat(outputOf(resp).get("recorded")).isEqualTo(2);

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ExecutorDecision::action)
                .containsExactly("SKIP", "HOLD");
        assertThat(captor.getAllValues()).extracting(ExecutorDecision::signalId)
                .containsExactly("sig-1", "sig-2");
        assertThat(captor.getAllValues()).allMatch(d -> !d.accepted());
        assertThat(captor.getAllValues()).allMatch(d -> "r1".equals(d.runId()));

        // Only the SKIP moves a signal status; HOLD leaves it alone.
        verify(signalRepo).markStatus("sig-1", "SKIPPED");
        verify(signalRepo, never()).markStatus(eq("sig-2"), any());
    }

    /** ENTER rows are written by place-entry; submit-decision must not duplicate them. */
    @Test
    void submitDecision_enterIsNotDuplicated() {
        JsonNode body = json("""
                {"decisions":[{"signal_id":"sig-2","symbol":"BBB","action":"ENTER","rationale":"x"}]}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, null, body);

        assertThat(outputOf(resp).get("recorded")).isEqualTo(0);
        verify(decisionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(any(), any());
    }

    /**
     * BLOCKER 4 — {@code /tools/add-tranche} already inserts its own decision row
     * ({@code accepted=true}, rationale {@code "tranche 2 added: <reason>"}; prod row
     * {@code id=107 | ACME | accepted=t}). Letting the model ALSO submit an {@code ADD_TRANCHE}
     * record here produced two rows per event, the second a phantom {@code accepted=false} that
     * contradicts the first — the same double-count the {@code ENTER} exclusion exists to
     * prevent. Exclude it identically.
     */
    @Test
    void submitDecision_addTrancheIsNotDuplicated() {
        JsonNode body = json("""
                {"decisions":[{"signal_id":"sig-3","symbol":"CCC","action":"ADD_TRANCHE","rationale":"new high"}]}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", body);

        assertThat(outputOf(resp).get("recorded")).isEqualTo(0);
        // Not an unknown action either: it is a KNOWN action deliberately owned by another
        // endpoint, exactly like ENTER. Counting it as unknown would fire a drift alarm on
        // correct behaviour.
        assertThat(outputOf(resp).get("unknown_actions")).isEqualTo(0);
        verify(decisionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(any(), any());
    }

    /**
     * BLOCKER 3 — the bridge stringifies tool arguments, so the model sends {@code decisions} as
     * a JSON <em>string</em> rather than an array. Verified in production
     * ({@code run_tool_calls}, 2026-08-03 06:11:47):
     * <pre>
     *   jsonb_typeof(input_json->'decisions') = string  ->  {"recorded": 0}
     *   {"decisions": "[{\\"signal_id\\":\\"61bfad16-…\\",\\"action\\":\\"SKIP\\",…}]"}
     * </pre>
     * The handler gated on {@code decisions.isArray()} and fell through to {@code recorded: 0}
     * silently — no {@code unknown_actions}, no log. Every SKIP stayed PENDING and was
     * re-evaluated the next run. This is the same stringification that broke the HiveMem
     * {@code where} filter, i.e. a systemic property of the bridge and not a one-off.
     */
    @Test
    void submitDecision_acceptsAStringifiedDecisionsArray() {
        JsonNode body = json("""
                {"input":{"decisions":"[{\\"signal_id\\":\\"sig-1\\",\\"symbol\\":\\"ACME\\",\\"action\\":\\"SKIP\\",\\"rationale\\":\\"thin\\"}]"}}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", body);

        assertThat(outputOf(resp).get("recorded")).isEqualTo(1);
        verify(decisionRepo, times(1)).insert(any());
        verify(signalRepo).markStatus("sig-1", "SKIPPED");
    }

    /** A doubly-encoded array (string containing a string) is still recoverable and still real
     *  bridge behaviour; recover it rather than dropping the run's whole decision set. */
    @Test
    void submitDecision_acceptsADoublyStringifiedDecisionsArray() {
        JsonNode body = json("""
                {"decisions":"\\"[{\\\\\\"signal_id\\\\\\":\\\\\\"sig-1\\\\\\",\\\\\\"symbol\\\\\\":\\\\\\"ACME\\\\\\",\\\\\\"action\\\\\\":\\\\\\"SKIP\\\\\\",\\\\\\"rationale\\\\\\":\\\\\\"thin\\\\\\"}]\\""}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", body);

        assertThat(outputOf(resp).get("recorded")).isEqualTo(1);
    }

    /** A single decision object sent where an array was declared is the other shape the bridge
     *  produces; accept it rather than answering a silent {@code recorded: 0}. */
    @Test
    void submitDecision_acceptsASingleDecisionObject() {
        JsonNode body = json("""
                {"decisions":{"signal_id":"sig-1","symbol":"ACME","action":"SKIP","rationale":"thin"}}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", body);

        assertThat(outputOf(resp).get("recorded")).isEqualTo(1);
    }

    /**
     * A {@code decisions} argument that genuinely cannot be read must be LOUD. Returning
     * {@code recorded: 0} to the agent and writing nothing anywhere is what let the stringified
     * case run undetected in production; an unusable argument has to be visible both to the agent
     * (so it can retry with the right shape) and in the log (so the daily analysis sees it).
     */
    @Test
    void submitDecision_unusableDecisionsArgumentIsReportedNotSwallowed() {
        JsonNode body = json("""
                {"decisions":"not json at all"}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(0);
        assertThat(output.get("error")).asString()
                .as("an unreadable decisions argument must name itself in the response")
                .contains("decisions");
        verify(decisionRepo, never()).insert(any());
    }

    /** An ABSENT {@code decisions} argument is a different thing from an unusable one: the model
     *  had nothing to record. It must not raise the error channel. */
    @Test
    void submitDecision_absentDecisionsIsNotAnError() {
        ResponseEntity<?> resp = controller.submitDecision(BEARER, "r1", json("{}"));

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(0);
        assertThat(output).doesNotContainKey("error");
    }

    /** An action outside the four known ones must never be swallowed silently. */
    @Test
    void submitDecision_unknownAction_isNotPersistedAndNotSilent() {
        JsonNode body = json("""
                {"decisions":[{"signal_id":"sig-9","symbol":"ZZZ","action":"FROLIC","rationale":"?"}]}
                """);

        ResponseEntity<?> resp = controller.submitDecision(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(0);
        // Visible to the agent as well as in the log — a dropped decision must be reported back.
        assertThat(output.get("unknown_actions")).isEqualTo(1);
        verify(decisionRepo, never()).insert(any());
        verify(signalRepo, never()).markStatus(any(), any());
    }

    @Test
    void submitDecision_nullBody_recordsZero() {
        ResponseEntity<?> resp = controller.submitDecision(BEARER, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("recorded")).isEqualTo(0);

        verify(decisionRepo, never()).insert(any());
    }

    // -------------------------------------------------------------------
    // fetch-open-positions
    // -------------------------------------------------------------------

    @Test
    void fetchOpenPositions_runsPipelineAndSerializes() {
        EnrichedPosition ep = new EnrichedPosition(1L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("104"),
                new BigDecimal("108"), new BigDecimal("2.0"), new BigDecimal("104"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of("X"),
                true, false, 1, true, "R_CONFIRMED", "sig-42", 0, 0.33, true, null, null);
        when(pipeline.run(eq("depot-1"), any())).thenReturn(List.of(ep));

        ResponseEntity<?> resp = controller.fetchOpenPositions(BEARER, "run-1");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        List<?> positions = (List<?>) output.get("positions");
        assertThat(positions).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) positions.get(0);
        assertThat(first.get("symbol")).isEqualTo("ACME");
        assertThat(first.get("signal_id")).isEqualTo("sig-42");
        assertThat(first.get("entry_filled")).isEqualTo(true);
        assertThat(first.get("current_price")).isEqualTo(new BigDecimal("108"));
        assertThat(first.get("chandelier_level")).isEqualTo(new BigDecimal("104"));
        assertThat(first.get("kill_criteria")).isEqualTo(List.of("X"));
        assertThat(first.get("trim_count")).isEqualTo(0);
        assertThat(first.get("suggested_fraction")).isEqualTo(0.33);

        @SuppressWarnings("unchecked")
        Map<String, Object> softTrigger = (Map<String, Object>) first.get("soft_trigger");
        assertThat(softTrigger.get("confirm_count")).isEqualTo(1);
        assertThat(softTrigger.get("chandelier_breach")).isEqualTo(true);
        assertThat(softTrigger.get("kill_criteria_breached")).isEqualTo(List.of("X"));

        @SuppressWarnings("unchecked")
        Map<String, Object> tranche2 = (Map<String, Object>) first.get("tranche2");
        assertThat(tranche2.get("eligible")).isEqualTo(true);
        assertThat(tranche2.get("reason")).isEqualTo("R_CONFIRMED");
    }

    @Test
    void fetchOpenPositions_authRejected() {
        ResponseEntity<?> resp = controller.fetchOpenPositions("Bearer wrong", "run-1");

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(pipeline);
    }

    @Test
    void fetchOpenPositions_serializesEntryFilledFalseForUnfilledEntry() {
        EnrichedPosition ep = new EnrichedPosition(1L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                null, null, null, null, null, 0, List.of(), List.of(),
                false, false, 0, false, null, "sig-42", 0, 0.33, false, null, null);
        when(pipeline.run(eq("depot-1"), any())).thenReturn(List.of(ep));

        ResponseEntity<?> resp = controller.fetchOpenPositions(BEARER, "run-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) output.get("positions")).get(0);
        assertThat(first.get("entry_filled")).isEqualTo(false);

        verifyNoInteractions(positionContextRepo);
    }

    @Test
    void fetchOpenPositions_entryFilled_writesPositionContext() {
        EnrichedPosition ep = new EnrichedPosition(1L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("108"), new BigDecimal("2.0"), new BigDecimal("104"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X", "Y"), List.of(),
                false, false, 1, false, null, "sig-42", 0, 0.33, true, null, null);
        when(pipeline.run(eq("depot-1"), any())).thenReturn(List.of(ep));

        ExecutorPosition position = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"));
        when(positionRepo.findById(1L)).thenReturn(position);

        ExecutorSignal signal = new ExecutorSignal("sig-42", "spin-hunter", "v1", "ACME", "BUY",
                0.8, "SPINOFF", List.of("X", "Y"), "6-12mo", new BigDecimal("100"), "ACCEPTED", null);
        when(signalRepo.findById("sig-42")).thenReturn(signal);

        controller.fetchOpenPositions(BEARER, "run-1");

        ArgumentCaptor<JsonNode> killCriteriaCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(positionContextRepo).upsertOnOpen(eq("depot-1"), eq("ACME"), isNull(),
                killCriteriaCaptor.capture(), eq("6-12mo"), isNull(), eq(new BigDecimal("95")),
                eq("executor"));
        assertThat(killCriteriaCaptor.getValue().toString()).contains("X").contains("Y");
    }

    /**
     * Regression for the ratchet-race finding: a position that transitions unfilled -> filled is
     * ratchet-eligible in the SAME {@code MaintenancePipeline} pass that builds the
     * {@link EnrichedPosition} handed to {@code recordPositionContext} — so
     * {@code EnrichedPosition.activeStop()} can already be the post-ratchet stop by the time it
     * gets here, not the placement-time initial stop. Because
     * {@link PositionContextRepository#upsertOnOpen} is {@code ON CONFLICT DO NOTHING}, using the
     * wrong value would freeze it permanently into {@code position_context.initial_stop}. Here
     * {@code ep.activeStop()} (99, already ratcheted up from 95) deliberately differs from the
     * position's true immutable {@code initialStop()} (95) to prove the write uses the latter.
     */
    @Test
    void fetchOpenPositions_entryFilled_writesImmutableInitialStopNotRatchetedActiveStop() {
        EnrichedPosition ep = new EnrichedPosition(1L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("99"),
                new BigDecimal("108"), new BigDecimal("2.0"), new BigDecimal("104"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X", "Y"), List.of(),
                false, false, 1, false, null, "sig-42", 0, 0.33, true, null, null);
        when(pipeline.run(eq("depot-1"), any())).thenReturn(List.of(ep));

        // True immutable initial stop (95) differs from the already-ratcheted active stop (99).
        ExecutorPosition position = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"));
        ExecutorPosition ratcheted = new ExecutorPosition(position.id(), position.connection(),
                position.symbol(), position.side(), position.qty(), position.entryPrice(),
                position.initialStop(), new BigDecimal("99"), position.tranche(), position.rValue(),
                position.killCriteria(), position.sourceSignalId(), position.sourceAgent(),
                position.entryDate(), position.mfe(), position.status(), position.brokerOrderId(),
                position.highestPrice(), position.mfeR(), position.softConfirmCount(),
                position.exitPrice(), position.realizedR(), position.exitReason(),
                position.closedAt(), position.stopOrderId(), position.sector(),
                position.entryDayHigh(), position.tranche2OrderId(), position.tranche2StopOrderId(),
                position.trimCount(), position.lowestPrice(), position.entryExpiresAt(),
                position.submittedLimitPrice(), position.pendingExitReason(), position.exitOrderId(),
                position.pendingExitFillPrice(), false, null, null);
        when(positionRepo.findById(1L)).thenReturn(ratcheted);

        ExecutorSignal signal = new ExecutorSignal("sig-42", "spin-hunter", "v1", "ACME", "BUY",
                0.8, "SPINOFF", List.of("X", "Y"), "6-12mo", new BigDecimal("100"), "ACCEPTED", null);
        when(signalRepo.findById("sig-42")).thenReturn(signal);

        controller.fetchOpenPositions(BEARER, "run-1");

        verify(positionContextRepo).upsertOnOpen(eq("depot-1"), eq("ACME"), isNull(),
                any(), eq("6-12mo"), isNull(), eq(new BigDecimal("95")), eq("executor"));
    }

    @Test
    void fetchOpenPositions_positionContextWriteFails_doesNotFailFetch() {
        EnrichedPosition ep = new EnrichedPosition(1L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("108"), new BigDecimal("2.0"), new BigDecimal("104"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of(),
                false, false, 1, false, null, "sig-42", 0, 0.33, true, null, null);
        when(pipeline.run(eq("depot-1"), any())).thenReturn(List.of(ep));
        when(signalRepo.findById("sig-42")).thenReturn(null);
        when(positionContextRepo.upsertOnOpen(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        ResponseEntity<?> resp = controller.fetchOpenPositions(BEARER, "run-1");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        assertThat((List<?>) output.get("positions")).hasSize(1);
    }

    @Test
    void fetchOpenPositions_writesSignalThesisToContext() {
        EnrichedPosition ep = new EnrichedPosition(1L, "depot-1", "HELE", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("108"), new BigDecimal("2.0"), new BigDecimal("104"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of(),
                false, false, 1, false, null, "sig1", 0, 0.33, true, null, null);
        when(pipeline.run(eq("depot-1"), any())).thenReturn(List.of(ep));

        ExecutorPosition position = openPosition(1L, "HELE", "BUY", new BigDecimal("100"),
                new BigDecimal("95"));
        when(positionRepo.findById(1L)).thenReturn(position);

        JsonNode thesis = json("""
                {"summary":"beat"}
                """);
        ExecutorSignal signal = new ExecutorSignal("sig1", "spin-hunter", "v1", "HELE", "BUY",
                0.8, "SPINOFF", List.of("X"), "6-12mo", new BigDecimal("100"), "ACCEPTED", null,
                thesis);
        when(signalRepo.findById("sig1")).thenReturn(signal);

        controller.fetchOpenPositions(BEARER, "run-1");

        ArgumentCaptor<JsonNode> thesisCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(positionContextRepo).upsertOnOpen(eq("depot-1"), eq("HELE"), isNull(),
                any(), eq("6-12mo"), thesisCaptor.capture(), eq(new BigDecimal("95")),
                eq("executor"));
        assertThat(thesisCaptor.getValue()).isNotNull();
        assertThat(thesisCaptor.getValue().get("summary").asString()).isEqualTo("beat");

        verify(positionContextRepo).updateContextIfNull(eq("depot-1"), eq("HELE"),
                thesisCaptor.capture(), any(), eq("6-12mo"), eq(new BigDecimal("95")));
    }

    @Test
    void fetchOpenPositions_mirrorsActiveStopForBuyOnly() {
        EnrichedPosition buyWithStop = new EnrichedPosition(1L, "depot-1", "HELE", "BUY",
                new BigDecimal("10"), new BigDecimal("200"), new BigDecimal("180.50"),
                new BigDecimal("210"), new BigDecimal("2.0"), new BigDecimal("204"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of(),
                false, false, 1, false, null, "sig-1", 0, 0.33, true, null, null);
        EnrichedPosition sellWithStop = new EnrichedPosition(2L, "depot-1", "SHRT", "SELL",
                new BigDecimal("10"), new BigDecimal("40"), new BigDecimal("50"),
                new BigDecimal("38"), new BigDecimal("2.0"), new BigDecimal("42"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of(),
                false, false, 1, false, null, "sig-2", 0, 0.33, true, null, null);
        EnrichedPosition buyWithNullStop = new EnrichedPosition(3L, "depot-1", "NOPX", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), null,
                new BigDecimal("105"), new BigDecimal("2.0"), new BigDecimal("101"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of(),
                false, false, 1, false, null, "sig-3", 0, 0.33, true, null, null);
        when(pipeline.run(eq("depot-1"), any()))
                .thenReturn(List.of(buyWithStop, sellWithStop, buyWithNullStop));

        controller.fetchOpenPositions(BEARER, "run-1");

        verify(positionContextRepo).updateActiveStopBySymbol(
                "depot-1", "HELE", new BigDecimal("180.50"));
        verify(positionContextRepo, never()).updateActiveStopBySymbol(
                eq("depot-1"), eq("SHRT"), any());
        verify(positionContextRepo, never()).updateActiveStopBySymbol(
                eq("depot-1"), eq("NOPX"), any());
    }

    // -------------------------------------------------------------------
    // exit-position
    // -------------------------------------------------------------------

    @Test
    void exitPosition_unfilledEntry_rejectedNotFilled_noBrokerCall() {
        // entry_expires_at != null marks a GTD entry with no confirmed fill (set at placement,
        // cleared by reconcile on fill / by expiry on cancel). An LLM exit on it would flatten
        // zero broker holdings and fabricate a close -> rejected NOT_FILLED, no broker call.
        ExecutorPosition unfilled = new ExecutorPosition(7L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", new BigDecimal("100"), null, 0, null, null,
                null, null, "stop-1", null, null, null, null, 0, null,
                "2026-07-03T00:00:42Z", null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(unfilled));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NOT_FILLED");

        verifyNoInteractions(gateway);
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("REJECT");
        assertThat(log.reasonCode()).isEqualTo("NOT_FILLED");
        assertThat(log.confidenceInDecision()).isEqualTo(0.7);
    }

    @Test
    void exitPosition_fullExit() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);
        assertThat(output.get("exit_reason")).isEqualTo("SOFT_CHANDELIER");

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE));

        ArgumentCaptor<BigDecimal> exitPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> rValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(7L), exitPriceCaptor.capture(), realizedRCaptor.capture(),
                eq("SOFT_CHANDELIER"), eq("FILL"), rValueCaptor.capture());
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("112");
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("2.4");
        // r_value is the entry/stop denominator computeR actually divided by (100 - 95).
        assertThat(rValueCaptor.getValue()).isEqualByComparingTo("5");

        verify(cooldownRepo).add(eq("ACME"), eq("SOFT_CHANDELIER"), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("SOFT_TRIGGER");
        assertThat(log.action()).isEqualTo("EXIT_FULL");
        assertThat(log.confidenceInDecision()).isEqualTo(0.7);

        verify(executorNotifier).notifyExit(any(), any(), any(), any(), any());
    }

    @Test
    void exitPosition_fullExitThatFillsImmediately_closesEveryOpenLegWithTheRow() {
        // The fifth lifecycle point. A FULL exit the broker fills on the spot closes the row
        // here, not through reconcile -- and used to leave its legs OPEN, so the book carried
        // OPEN legs under a CLOSED position: exactly the state V45 established as impossible and
        // V46 aborts on.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(legRepo.findOpenByPosition(7L)).thenReturn(List.of(
                new ExecutorPositionLeg(10L, 7L, 1, "ord-1", "stop-1", new BigDecimal("4"),
                        ExecutorPositionLeg.OPEN, null, null, null),
                new ExecutorPositionLeg(11L, 7L, 2, "ord-2", "stop-2", new BigDecimal("6"),
                        ExecutorPositionLeg.OPEN, null, null, null)));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO,
                        new BigDecimal("112"), "close-1", List.of(), false));

        controller.exitPosition(BEARER, "run-1", json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}
                """));

        verify(positionRepo).close(eq(7L), any(), any(), eq("SOFT_CHANDELIER"), eq("FILL"), any());
        // Each leg carries the row's own exit price and reason -- the whole position exited at
        // one fill, so there is no per-leg price to distinguish.
        ArgumentCaptor<BigDecimal> legExitPrice = ArgumentCaptor.forClass(BigDecimal.class);
        verify(legRepo).closeLeg(eq(10L), legExitPrice.capture(), eq("SOFT_CHANDELIER"), any());
        assertThat(legExitPrice.getValue()).isEqualByComparingTo("112");
        verify(legRepo).closeLeg(eq(11L), any(), eq("SOFT_CHANDELIER"), any());
    }

    @Test
    void exitPosition_partialTrim_leavesLegRowsToReconcile() {
        // The stated counterpart of the test above: a PARTIAL close deliberately does NOT touch
        // the legs. The broker spreads a partial across its own tranches and reports only a
        // total, so any per-leg split written here would be our arithmetic, not the broker's --
        // the exact defect the V45 backfill was rewritten to avoid. Reconcile converges each leg
        // to its own working stop next pass. Pinned so the omission reads as a decision.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), any()))
                .thenReturn(new CloseResult(new BigDecimal("3"), new BigDecimal("7"),
                        new BigDecimal("112"), "close-1", List.of(), false));

        controller.exitPosition(BEARER, "run-1", json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7,"fraction":0.33}
                """));

        verify(positionRepo).recordTrim(eq(7L), any(), anyInt(), any(), anyBoolean());
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
        verify(legRepo, never()).syncLegQty(anyLong(), any());
    }

    @Test
    void exitPosition_fullExitWithoutFillPrice_stampsPendingExitInsteadOfClosing() {
        // Verified prod incident (PSMT): a flatten that is merely accepted (no avgFillPrice yet)
        // must not be booked as closed here — that books a wrong exit price/R and can mismatch
        // the broker's still-working exit order. Stamp pending and let ReconcileService finalize.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, null, "close-9", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("pending")).isEqualTo(true);

        verify(positionRepo).markPendingExit(eq(7L), eq("SOFT_CHANDELIER"), eq("close-9"),
                isNull(), eq(FIXED_NOW));
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any(), any());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());
    }

    @Test
    void exitPosition_vistierieEnvelope_fullExit() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"run_id":"r1","tool_name":"exit_position",
                 "input":{"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "r1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);
        assertThat(output.get("exit_reason")).isEqualTo("SOFT_CHANDELIER");

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE));
        verify(positionRepo).close(eq(7L), any(), any(), eq("SOFT_CHANDELIER"), eq("FILL"), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("EXIT_FULL");
        assertThat(logCaptor.getValue().confidenceInDecision()).isEqualTo(0.7);
    }

    @Test
    void exitPosition_noOpenPosition() {
        when(positionRepo.findOpen()).thenReturn(List.of());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER"}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NO_OPEN_POSITION");

        verify(gateway, never()).flatten(any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
    }

    @Test
    void exitPosition_brokerError() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenThrow(new BrokerUnavailableException("broker down"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER"}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("ESCALATE");
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
    }

    @Test
    void exitPosition_nullBody() {
        when(positionRepo.findOpen()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NO_OPEN_POSITION");

        verify(gateway, never()).flatten(any(), any(), any());
    }

    @Test
    void exitPosition_authRejected() {
        ResponseEntity<?> resp = controller.exitPosition("Bearer wrong", "run-1", json("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(gateway, positionRepo, decisionLogRepo, cooldownRepo);
    }

    // -------------------------------------------------------------------
    // exit-position: scale-out — fraction parameter + code-enforced trim ladder
    // -------------------------------------------------------------------

    @Test
    void exitPosition_invalidFraction_schemaInvalid_noBrokerCall() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.4}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");

        verifyNoInteractions(gateway);
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());
    }

    @Test
    void exitPosition_trim033_freshPosition_scalesOutAndBumpsLadder() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenReturn(new CloseResult(new BigDecimal("3"), new BigDecimal("7"), new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33)));
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        // qty 10 * (1-0.33) = 6.7 would floor to 6 by Dracul's own arithmetic, but the gateway
        // mock reports remainingQty 7 — the broker's number must win over the local computation.
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("7")), eq(1), eq(List.of()), eq(false));
        verify(cooldownRepo, never()).add(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("TRIM");
        assertThat(log.reasonCode()).isNull();
        assertThat(log.orderJson().path("fraction").asDouble()).isEqualTo(0.33);
        assertThat(log.orderJson().has("qty_closed")).isTrue();
        assertThat(log.orderJson().path("qty_remaining").asDouble()).isEqualTo(7.0);
    }

    @Test
    void exitPosition_trim033_roundHundredQty_noDoubleComplementDrift() {
        // Regression: 1 - 0.33 computed in primitive double is 0.6699999999999999, which floors
        // qty=100 to 66 instead of 67. The complement must be computed in BigDecimal so qty=100
        // trims to exactly remaining 67 / closed 33.
        //
        // The gateway mock reports NO broker quantities (null/null) so this actually exercises
        // the local-arithmetic FALLBACK path -- a mock returning matching closedQty/remainingQty
        // here would make this test pass even if the BigDecimal complement regressed back to a
        // primitive double, because the asserted value would come from the mock, not from the
        // controller's own arithmetic (fix round 1 finding: this test was vacuous under mutation).
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("100"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenReturn(new CloseResult(null, null, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat((BigDecimal) output.get("qty_remaining")).isEqualByComparingTo("67");
        assertThat((BigDecimal) output.get("qty_closed")).isEqualByComparingTo("33");

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33)));
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("67")), eq(1), eq(List.of()), eq(false));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().orderJson().path("qty_remaining").asDouble()).isEqualTo(67.0);
        assertThat(logCaptor.getValue().orderJson().path("qty_closed").asDouble()).isEqualTo(33.0);
    }

    @Test
    void exitPosition_trim033_qty200_remaining134() {
        // Same fallback-path reasoning as the qty=100 test above: null/null broker quantities so
        // the asserted 134 can only come from the controller's own BigDecimal complement.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("200"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenReturn(new CloseResult(null, null, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        controller.exitPosition(BEARER, "run-1", body);

        // 200 * 0.67 = 134 exactly; double-complement drift would have produced 133.
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("134")), eq(1), eq(List.of()), eq(false));
    }

    @Test
    void exitPosition_fractionBelowLadderFloor_rejectsSchemaInvalid() {
        // trim_count=1 -> ladder floor is 0.5; LLM may not undercut with 0.33.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("7"), 1);
        when(positionRepo.findOpen()).thenReturn(List.of(open));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("SCHEMA_INVALID");
        assertThat(String.valueOf(output.get("reasoning"))).contains("0.5");

        verifyNoInteractions(gateway);
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
    }

    @Test
    void exitPosition_fraction1_explicit_fullExitPathUnchanged() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","fraction":1.0}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE));
        verify(positionRepo).close(eq(7L), any(), any(), eq("SOFT_CHANDELIER"), eq("FILL"), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(cooldownRepo).add(eq("ACME"), eq("SOFT_CHANDELIER"), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("EXIT_FULL");
    }

    @Test
    void exitPosition_trimRemainingBelowOneShare_treatedAsFullExit_brokerFlattensFully() {
        // qty=2, fraction=0.5 -> remaining = floor(2*0.5)=1, still >=1 share so this is NOT
        // the below-1-share case; use qty=1 instead so remaining floors to 0 and full-exit
        // semantics kick in (close + cooldown, not recordTrim).
        //
        // CRITICAL: when the book treats this as a full exit, the BROKER must be flattened
        // fully too (fraction ONE, not 0.5) — otherwise the book closes while the broker
        // still holds an unmanaged remainder.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("1"), 1);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE));
        verify(gateway, never()).flatten(any(), any(), eq(BigDecimal.valueOf(0.5)));
        verify(positionRepo).close(eq(7L), any(), any(), eq("SCALE_OUT"), eq("FILL"), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(cooldownRepo).add(eq("ACME"), eq("SCALE_OUT"), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("EXIT_FULL");
    }

    // -------------------------------------------------------------------
    // exit-position: scale-out — books what the BROKER did, and leg-restore alerting
    // -------------------------------------------------------------------

    @Test
    void trimBooksTheBrokerQuantitiesNotItsOwn() {
        // position qty 23, fraction 0.5: Dracul's own floor(23*0.5)=11, the broker floors the
        // other side and reports closedQty 11 / remainingQty 12 — the broker's numbers must win
        // everywhere: recordTrim, order_json and the response body.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("23"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.5))))
                .thenReturn(new CloseResult(new BigDecimal("11"), new BigDecimal("12"),
                        new BigDecimal("40"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat((BigDecimal) output.get("qty_closed")).isEqualByComparingTo("11");
        assertThat((BigDecimal) output.get("qty_remaining")).isEqualByComparingTo("12");

        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("12")), eq(1), eq(List.of()), eq(false));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        JsonNode orderJson = logCaptor.getValue().orderJson();
        assertThat(orderJson.path("qty_closed").asDouble()).isEqualTo(11.0);
        assertThat(orderJson.path("qty_remaining").asDouble()).isEqualTo(12.0);
    }

    @Test
    void trimFallsBackToLocalArithmeticWhenTheBrokerDoesNotReportQuantities() {
        // A provider that does not report closed/remaining qty -> fall back to Dracul's own
        // floor(qty * (1-fraction)) arithmetic, not a null/NPE.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.5))))
                .thenReturn(new CloseResult(null, null, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat((BigDecimal) output.get("qty_closed")).isEqualByComparingTo("5");
        assertThat((BigDecimal) output.get("qty_remaining")).isEqualByComparingTo("5");
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("5")), eq(1), eq(List.of()), eq(false));
    }

    @Test
    void trimFallsBackToLocalArithmeticForBothWhenOnlyOneBrokerQuantityIsReported() {
        // The two null fallbacks are NOT independent: a provider reporting only closedQty (or
        // only remainingQty) must fall back to the LOCAL arithmetic for BOTH fields, never mix
        // one broker-reported number with one locally-computed one -- that would yield
        // qty_closed + qty_remaining != position.qty(), exactly the OutcomeBatchJob weighted-R
        // inflation this task removes. Not reachable through today's gateway; guards against a
        // future provider that reports only one of the pair.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        // Only closedQty reported (7, NOT matching the local floor of 5) -- remainingQty is null.
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.5))))
                .thenReturn(new CloseResult(new BigDecimal("7"), null, new BigDecimal("112"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        // Both fall back to local arithmetic (qty 10 * 0.5 = 5 closed / 5 remaining), NOT 7
        // closed paired with a locally-computed remaining.
        assertThat((BigDecimal) output.get("qty_closed")).isEqualByComparingTo("5");
        assertThat((BigDecimal) output.get("qty_remaining")).isEqualByComparingTo("5");
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("5")), eq(1), eq(List.of()), eq(false));
    }

    @Test
    void trimBooksBrokerQuantitiesEvenWhenAPendingOppositeCloseMakesThemNotSumToTheOriginalQty() {
        // Pins a known, documented (not fixed here) gap: SaxoBrokerProvider.flatten's M-T6
        // idempotent-retry lookup subtracts any already-pending opposite-side Market quantity
        // from the requested close before placing this call's order (a prior trim whose HTTP
        // response was lost, but which the broker already accepted). closedQty is that reduced
        // "this call's own contribution"; remainingQty is available.subtract(closeQty) using the
        // FULL originally-requested closeQty (a correct PROJECTION of what remains once both the
        // pending order and this one fill). The two together therefore fall short of the original
        // position qty by exactly the pending quantity that was never separately booked (the
        // lost-response trim's own decision_log/order_json row was never written either, since
        // the caller never got a response to log). Position qty 46, requested close 23 (0.5
        // fraction), 10 already pending -> closedQty=13, remainingQty=23, sum=36 != 46.
        //
        // Dracul books exactly what the broker reports without trying to reconcile the gap:
        // recordTrim's new qty is remainingQty (23, the correct final-remaining projection), and
        // qty_closed (13) is logged as-is, undercounting this trim's true contribution to the
        // OutcomeBatchJob's weighted-R by the 10 shares the lost trim closed. This is a real, if
        // narrow, analytics distortion -- not a book-correctness bug: the position's own qty
        // column stays accurate (23 is genuinely what remains once both closes fill), only the
        // qty_closed audit trail undercounts. Whether to warn/reconcile here is left to a future
        // change; this test only pins the current, documented behaviour.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("46"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.5))))
                .thenReturn(new CloseResult(new BigDecimal("13"), new BigDecimal("23"),
                        new BigDecimal("40"), "close-1", List.of(), false));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat((BigDecimal) output.get("qty_closed")).isEqualByComparingTo("13");
        assertThat((BigDecimal) output.get("qty_remaining")).isEqualByComparingTo("23");
        // 13 + 23 = 36, NOT the original 46 -- the gap this test documents.
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("23")), eq(1), eq(List.of()), eq(false));
    }

    @Test
    void trimPersistsTheRestoredLegIds() {
        // Gateway returns two restored protective legs on a successful trim -> recordTrim
        // receives them unchanged (plus the collapsed flag) so the stop columns get repointed.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("3"), new BigDecimal("90")),
                new RestoredLeg("old-2", "new-2", new BigDecimal("3"), new BigDecimal("90")));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenReturn(new CloseResult(new BigDecimal("3"), new BigDecimal("7"),
                        new BigDecimal("112"), "close-1", legs, true));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        controller.exitPosition(BEARER, "run-1", body);

        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("7")), eq(1), eq(legs), eq(true));
    }

    @Test
    void aRejectedFlattenRepointsTheLegRowsToTheRestoredStopIds() {
        // The leg rows carry the ids StopRatchetService actually addresses. A rollback that
        // re-issued the protective legs must land on them too, or the ratchet keeps patching
        // orders that no longer exist and fails LEG_NOT_FOUND on every run.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(legRepo.findOpenByPosition(7L)).thenReturn(List.of(
                new ExecutorPositionLeg(10L, 7L, 1, "ord-1", "stop-1", new BigDecimal("6"),
                        ExecutorPositionLeg.OPEN, null, null, null),
                new ExecutorPositionLeg(11L, 7L, 2, "ord-2", "stop-2", new BigDecimal("4"),
                        ExecutorPositionLeg.OPEN, null, null, null)));
        // The rollback re-issued tranche 1's stop and reported nothing for tranche 2's.
        List<RestoredLeg> legs = List.of(
                new RestoredLeg("stop-1", "stop-1b", new BigDecimal("6"), new BigDecimal("90")));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenThrow(new BrokerRejectedException("rollback left the position unprotected",
                        "LEG_RESTORE_FAILED_UNPROTECTED", legs));

        controller.exitPosition(BEARER, "run-1", json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """));

        // Claimed by a restored leg -> repointed to the new id.
        verify(legRepo).repointLegStop(10L, "stop-1b");
        // Unclaimed -> nulled, not left stale. A null id is a visible protection gap; a stale one
        // looks live and silently patches an order the broker already cancelled.
        verify(legRepo).repointLegStop(11L, null);
    }

    @Test
    void unprotectedRejectionRaisesACriticalAlert() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("10"), new BigDecimal("90")));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenThrow(new BrokerRejectedException("rollback left the position unprotected",
                        "LEG_RESTORE_FAILED_UNPROTECTED", legs));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(telegram).notifyAlert(eq("ACME"), eq("LEG_RESTORE_FAILED_UNPROTECTED"), eq("CRITICAL"), any());

        // The trim did not happen -- must NOT reuse recordTrim (it would wrongly reset
        // soft_confirm_count / stop_legs_collapsed). Only the stop-leg columns are repointed.
        verify(positionRepo).repointStopLegs(eq(7L), eq(legs));
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        // One vocabulary with HardTriggerService: every rejection that is not "the position is
        // already gone" is BROKER_REJECTED, and Agora's wire code lives in a queryable field.
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.inputsSnapshot().path("reject_code").asString())
                .isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        assertThat(log.reasoning()).contains("[LEG_RESTORE_FAILED_UNPROTECTED]");
    }

    @Test
    void aTransientBrokerFailureDoesNotRaiseAnAlert() {
        // Plain BrokerUnavailableException (not a business rejection) keeps today's behaviour:
        // ESCALATE row, no Telegram alert.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenThrow(new BrokerUnavailableException("timeout"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(positionRepo, never()).repointStopLegs(anyLong(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
    }

    @Test
    void vanishedPositionOnSoftExitFlatten_escalatesAsAlreadyGone() {
        // Real incident (2026-08-24, RGNX): the broker had long since stopped the position out,
        // but the book still held it OPEN. The flatten call correctly reaches the broker and gets
        // an explicit verdict back -- "no open position" -- which must not be filed as an outage.
        // NO_POSITION is the reject code Agora's FlattenTool actually emits for this definite case
        // (SaxoBrokerProvider.resolveNetPosition -> FlattenTool's NO_POSITION mapping), not a code
        // invented for this test. Distinct (fix round 2) from the generic NOT_FOUND -- see
        // aGenericNotFoundRejection_isNotFiledAsAlreadyGone below.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenThrow(new BrokerRejectedException(
                        "agora order rejected [NO_POSITION]: no open position: ACME",
                        "NO_POSITION", List.of()));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(positionRepo, never()).repointStopLegs(anyLong(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("POSITION_ALREADY_GONE");
        assertThat(log.reasonCode()).isNotEqualTo("BROKER_UNAVAILABLE");
        // The full wording is pinned, not just the code: Task 5 shipped a null-interpolating
        // sentence past a reason-code-only test, so this one asserts the exact text and that it
        // never claims an outage or interpolates a null.
        assertThat(log.reasoning()).isEqualTo("position already gone during soft-exit flatten: "
                + "agora order rejected [NO_POSITION]: no open position: ACME");
        assertThat(log.reasoning()).doesNotContain("null");
        assertThat(log.reasoning()).doesNotContain("unavailable");
    }

    @Test
    void aGenericNotFoundRejection_isNotFiledAsAlreadyGone() {
        // Fix round 2: a generic NOT_FOUND (an HTTP 404 on some OTHER read/write inside Agora's
        // flatten -- e.g. the closing POST of this very partial close hitting safeWriteError on a
        // 404) must NOT be folded into POSITION_ALREADY_GONE. It carries no reject code this
        // codebase names, so it also proves the null-reason_code fallback: an unqueryable NULL
        // reason_code is as good as losing the escalation row.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenThrow(new BrokerRejectedException(
                        "agora order rejected [NOT_FOUND]: Resource not found (HTTP 404)",
                        "NOT_FOUND", List.of()));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        controller.exitPosition(BEARER, "run-1", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        // reason_code 'NOT_FOUND' is retired, deliberately: historical production rows carrying
        // that string meant "the position is gone", while THIS case says nothing about whether it
        // exists. The generic 404 now files as BROKER_REJECTED with the wire code in a queryable
        // field, so the old string is never written again and every surviving row with it is
        // unambiguously historical.
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.reasonCode()).isNotEqualTo("POSITION_ALREADY_GONE");
        assertThat(log.inputsSnapshot().path("reject_code").asString()).isEqualTo("NOT_FOUND");
        // Full text pinned, not just a substring check (fix round 3: reason-code-plus-
        // doesNotContain is the same shape that let Task 5's null-interpolating sentence ship
        // green).
        assertThat(log.reasoning()).isEqualTo("broker rejected soft-exit flatten [NOT_FOUND]: "
                + "agora order rejected [NOT_FOUND]: Resource not found (HTTP 404)");
        assertThat(log.reasoning()).doesNotContain("null");
        assertThat(log.reasoning()).doesNotContain("already gone");
    }

    @Test
    void aNullRejectCode_fallsBackToADefinedReasonCode() {
        // Agora can omit rejectCode entirely (see StopRatchetService.escalateModifyFailure's own
        // "no reject code" handling for the same gap on the ratchet path). A null reason_code on
        // an ESCALATE row is unqueryable -- give it a defined name instead of losing the row.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenThrow(new BrokerRejectedException(
                        "agora order rejected: some unmapped reason", null, List.of()));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        controller.exitPosition(BEARER, "run-1", body);

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.inputsSnapshot().path("reject_code").isNull()).isTrue();
        // Full text pinned too (fix round 3). "no reject code" rather than an interpolated null.
        assertThat(log.reasoning()).isEqualTo("broker rejected soft-exit flatten [no reject code]: "
                + "agora order rejected: some unmapped reason");
        assertThat(log.reasoning()).doesNotContain("null");
    }

    @Test
    void aRejectionThatRolledBackStillPersistsTheNewLegIds() {
        // LEG_RESTORE_FAILED (not the unprotected variant): the trim itself is rejected, so qty,
        // trim_count and soft_confirm_count must NOT change, but Agora rolled protection back and
        // issued new leg ids that must still be persisted so Dracul doesn't keep addressing
        // cancelled orders.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("10"), new BigDecimal("90")),
                new RestoredLeg("old-2", "new-2", new BigDecimal("10"), new BigDecimal("90")));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenThrow(new BrokerRejectedException("cancel incomplete, rolled back",
                        "LEG_RESTORE_FAILED", legs));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        // Not recordTrim -- no trim happened, so qty/trim_count/soft_confirm_count must not be
        // touched at all. Only the stop-leg id columns are repointed via repointStopLegs.
        verify(positionRepo).repointStopLegs(eq(7L), eq(legs));
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());

        verify(telegram, never()).notifyAlert(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.inputsSnapshot().path("reject_code").asString())
                .isEqualTo("LEG_RESTORE_FAILED");
    }

    @Test
    void aRejectionBeforeTheLegCancelLoopEverRunsMustNotRepointStopLegs() {
        // BLOCKER fix (whole-branch review, 2026-08-05): CLOSE_ALREADY_PENDING (and every other
        // Saxo reject that fires before SaxoBrokerProvider.flatten's leg-cancel loop --
        // INVALID_FRACTION, SYMBOL, QTY_EXCEEDS_POSITION, QTY_ROUNDED_TO_ZERO -- plus every
        // Alpaca flatten rejection, which never cancels a leg at all) carries a legitimately
        // EMPTY protectiveLegs() because Agora never touched a leg. Before this fix,
        // repointStopLegs ran unconditionally on ANY BrokerRejectedException and would have
        // nulled BOTH live stop columns here -- permanently orphaning working stop orders that
        // were never cancelled. Scenario: a prior trim's HTTP response was lost, the broker
        // already holds a pending partial close, and the next exit_position hits the idempotency
        // check -> CLOSE_ALREADY_PENDING with no leg touched.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("46"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.5))))
                .thenThrow(new BrokerRejectedException(
                        "a close of >= the requested size is already working",
                        "CLOSE_ALREADY_PENDING", List.of()));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_ERROR");

        // The stop columns must stay exactly as they were -- repointStopLegs must never even be
        // called, let alone with an empty list that would null both columns.
        verify(positionRepo, never()).repointStopLegs(anyLong(), any());
        // The leg table needs the same negative pairing as the columns. repointLegStops treats
        // "not named by a restored leg" as "dead" and NULLS the id, so hoisting it out of the
        // legCancelWasAttempted guard would orphan working stop orders on the leg rows exactly
        // the way it would on the columns -- and with only the column assertion above, the suite
        // would stay green while it happened.
        verify(legRepo, never()).repointLegStop(anyLong(), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());

        verify(telegram, never()).notifyAlert(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.inputsSnapshot().path("reject_code").asString())
                .isEqualTo("CLOSE_ALREADY_PENDING");
    }

    @Test
    void unprotectedRejectionWithAnEmptyLegListStillRepointsBothColumnsToNull() {
        // The worst case the gate must still let through: LEG_RESTORE_FAILED_UNPROTECTED CAN
        // legitimately carry an empty protectiveLegs() (interleaveRollback stopped with nothing
        // live at all -- e.g. a single-leg position, see SaxoBrokerProvider's own single-leg
        // rollback limitation). Unlike CLOSE_ALREADY_PENDING above, Agora DID touch a leg here, so
        // repointStopLegs must still run and null both columns (repointStopLegs(id, List.of())
        // nulls any currently-recorded id, since none is named as a replaces target).
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("46"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.5))))
                .thenThrow(new BrokerRejectedException(
                        "rollback left the position unprotected", "LEG_RESTORE_FAILED_UNPROTECTED",
                        List.of()));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        controller.exitPosition(BEARER, "run-1", body);

        verify(positionRepo).repointStopLegs(eq(7L), eq(List.of()));
        verify(telegram).notifyAlert(eq("ACME"), eq("LEG_RESTORE_FAILED_UNPROTECTED"), eq("CRITICAL"), any());
    }

    // -------------------------------------------------------------------
    // add-tranche
    // -------------------------------------------------------------------

    @Test
    void addTranche_eligible_nonDegenerateWeightedAverage() {
        // 10@100 held + 7@102 SUBMITTED -> the INTENDED weighted-average entry is
        // (10*100 + 7*102) / 17 = 100.823529 on 17 shares. That pair is a notification figure
        // only: the tranche-2 limit is merely working, so the book keeps 10@100 until the broker
        // reports the fill (BUG-S9 — `qty` means shares HELD).
        ExecutorPosition open = openPosition(11L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-11", "stop-11", "tp-11", "t2-sig-1", OrderStatus.WORKING));

        // price=102, trancheAmount=750 -> sizer floors qty to 7 (750/102 = 7.35).
        EntryContext ctx = new EntryContext(
                new AccountSnapshot(new BigDecimal("10000"), new BigDecimal("10000"), "USD"),
                new BigDecimal("102"), new BigDecimal("2"), null, new BigDecimal("500000"),
                new BigDecimal("103"), "TECH", List.of(), List.of(), List.of(), 0, 0L,
                new BigDecimal("750"), new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of(), BigDecimal.ONE, List.of(), "USD", null, new BigDecimal("2"), Map.of());
        when(assembler.assembleForSymbol(any())).thenReturn(ctx);

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(((BigDecimal) output.get("qty"))).isEqualByComparingTo("7");

        ArgumentCaptor<BigDecimal> qtyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> entryCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateTranche2(eq(11L), qtyCaptor.capture(), entryCaptor.capture(),
                eq("brk-11"), eq("stop-11"));
        assertThat(qtyCaptor.getValue()).isEqualByComparingTo("10");
        assertThat(entryCaptor.getValue()).isEqualByComparingTo("100");

        // The intended totals still reach the operator notification.
        ArgumentCaptor<BigDecimal> notifyQtyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> notifyEntryCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(executorNotifier).notifyTranche2(any(), any(), any(), notifyQtyCaptor.capture(),
                notifyEntryCaptor.capture(), any(), any());
        assertThat(notifyQtyCaptor.getValue()).isEqualByComparingTo("17");
        assertThat(notifyEntryCaptor.getValue()).isEqualByComparingTo("100.823529");
    }

    @Test
    void addTranche_eligible_placesSecondTranche() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", "tp-2", "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("reason")).isEqualTo("R_CONFIRMED");
        assertThat(((BigDecimal) output.get("qty"))).isEqualByComparingTo("10");

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        assertThat(req.symbol()).isEqualTo("ACME");
        assertThat(req.side()).isEqualTo("BUY");
        assertThat(req.qty()).isEqualByComparingTo("10");
        // stop-2 leg uses the position's EXISTING active stop (95), not a re-derived stop window.
        assertThat(req.stopLossStop()).isEqualByComparingTo("93.00");
        assertThat(req.clientRef()).isEqualTo("t2-sig-1");

        ArgumentCaptor<BigDecimal> qtyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> entryCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateTranche2(eq(7L), qtyCaptor.capture(), entryCaptor.capture(),
                eq("brk-2"), eq("stop-2"));
        // BUG-S9: the tranche-2 limit is WORKING, so the book stays at the 10 shares actually
        // held. The intended 20 lands only once ReconcileService sees the broker's larger
        // position; asserting 20 here is what pinned the flatten-on-phantom-shares bug.
        assertThat(qtyCaptor.getValue()).isEqualByComparingTo("10");
        assertThat(entryCaptor.getValue()).isEqualByComparingTo("100");

        ArgumentCaptor<ExecutorDecision> decisionCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decisionCaptor.capture());
        ExecutorDecision decision = decisionCaptor.getValue();
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.rationale()).isEqualTo("tranche 2 added: R_CONFIRMED");
        assertThat(decision.brokerOrderId()).isEqualTo("brk-2");

        verify(executorNotifier).notifyTranche2(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void tranche2IsPlacedWithoutATakeProfit() {
        // Der synthetische 3R-Take-Profit (+28 % vom Entry) war der Auslöser der
        // Saxo-Fehlkette: TooFarFromEntryOrder → Fallback → 429 → Retry → 409.
        // Tranche 2 braucht keinen eigenen Zielkurs — der Exit-Lifecycle steuert den
        // Ausstieg der Gesamtposition.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        controller.addTranche(BEARER, "run-1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        // The old 3R synthesis would have produced 100 + 3*(100-95) = 115 here.
        assertThat(req.takeProfitLimit()).isNull();
        // ...but the stop leg is untouched: a tranche must never be unguarded.
        assertThat(req.stopLossStop()).isEqualByComparingTo("93.00");
    }

    @Test
    void theEntryPathAlsoPlacesWithoutATarget() {
        // Der Entry-Pfad erfindet seit 2026-07-26 ebenfalls keinen Zielkurs mehr — dieselbe
        // Saxo-Ablehnung (TooFarFromEntryOrder) hätte sonst auch hier das Bracket samt Stop
        // gerissen. Nur ein explizit gelieferter take_profit wird noch durchgereicht.
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        controller.placeEntry(BEARER, "run-1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNull();
    }

    @Test
    void addTranche_dbFailureAfterPlacedBracket_escalatesOrphanedOrder() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("bracket-2", "stop-2", "tp-2", "t2-sig-1", OrderStatus.WORKING));
        doThrow(new RuntimeException("db down")).when(positionRepo)
                .updateTranche2(eq(7L), any(), any(), any(), any());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("ORPHANED_ORDER");
        assertThat(output.get("broker_order_id")).isEqualTo("bracket-2");

        verify(telegram).notifyAlert(eq("ACME"), eq("ORPHANED_ORDER"), eq("CRITICAL"), contains("bracket-2"));

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("ORPHANED_ORDER");
        assertThat(decCaptor.getValue().brokerOrderId()).isEqualTo("bracket-2");
    }

    @Test
    void addTranche_acceptedAuditInsertFails_stillReportsPlacedTrue() {
        // updateTranche2 succeeds durably; only the accepted-audit decisionRepo.insert throws.
        // The response must NOT flip into a false ORPHANED_ORDER -- that would contradict the
        // already-persisted tranche update.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", "tp-2", "t2-sig-1", OrderStatus.WORKING));
        doThrow(new RuntimeException("audit db down")).when(decisionRepo)
                .insert(argThat(d -> d != null && d.accepted()));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("reason")).isEqualTo("R_CONFIRMED");

        verify(positionRepo).updateTranche2(eq(7L), any(), any(), eq("brk-2"), eq("stop-2"));
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
    }

    @Test
    void addTranche_nullSourceSignalId_clientRefFallsBackToPositionId() {
        ExecutorPosition open = new ExecutorPosition(42L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 1, null, List.of("X"), null, "hunter",
                "2026-06-01", null, "OPEN", "brk-1", new BigDecimal("100"), null, 0,
                null, null, null, null, null, null, null, null, null, 0, null, null,
                null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-42", "stop-42", "tp-42", "t2-pos-42", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        controller.addTranche(BEARER, "run-1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().clientRef()).isEqualTo("t2-pos-42");
    }

    @Test
    void addTranche_rejectsWhenTrancheLimitReached() {
        ExecutorPosition open = new ExecutorPosition(7L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 2, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", new BigDecimal("100"), null, 0,
                null, null, null, null, null, null, null, null, null, 0, null, null,
                null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("MAX_TRANCHE");

        verify(assembler, never()).assembleForSymbol(any());
        verify(gateway, never()).placeBracket(any(), any());
        verify(tranche2Detector, never()).detect(any(), any(), any(), any());

        ArgumentCaptor<ExecutorDecision> decisionCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().accepted()).isFalse();
        assertThat(decisionCaptor.getValue().rejectReason()).isEqualTo("MAX_TRANCHE");
    }

    @Test
    void addTranche_notEligible_noGatewayCall() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(false, null));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NOT_ELIGIBLE");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).updateTranche2(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<ExecutorDecision> decisionCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().accepted()).isFalse();
        assertThat(decisionCaptor.getValue().rejectReason()).isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void addTranche_heatLimitBreach_noGatewayCall() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        // heat limit = 0.06 * 10000 = 600; existing openHeat of 590 + new risk (50) breaches it.
        when(assembler.assembleForSymbol(any())).thenReturn(withOpenHeat(happyContext(), new BigDecimal("590")));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("HEAT_LIMIT");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).updateTranche2(anyLong(), any(), any(), any(), any());
    }

    @Test
    void addTranche_noOpenPosition() {
        when(positionRepo.findOpen()).thenReturn(List.of());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NO_POSITION");

        verify(assembler, never()).assembleForSymbol(any());
        verify(gateway, never()).placeBracket(any(), any());

        ArgumentCaptor<ExecutorDecision> decisionCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().rejectReason()).isEqualTo("NO_POSITION");
    }

    @Test
    void addTranche_dataUnavailable_rejectsBeforeSizing() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(assembler.assembleForSymbol(any())).thenReturn(unavailableContext());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("DATA_UNAVAILABLE");

        verifyNoInteractions(tranche2Detector);
        verify(gateway, never()).placeBracket(any(), any());

        ArgumentCaptor<ExecutorDecision> decisionCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().rejectReason()).isEqualTo("DATA_UNAVAILABLE");
    }

    // -------------------------------------------------------------------
    // add-tranche: tick rounding — decision raw, mechanics rounded (Task 4)
    // -------------------------------------------------------------------

    @Test
    void addTranche_eligibilityUsesRawPrice_brokerGetsRoundedPrice() {
        // entryPrice=68, initialStop=64.976 -> rPerShare=3.024, so +1R sits at 71.024, not on the
        // 0.01 tick grid. ctx.price()=71.026 is raw-eligible: rMultiple = 3.026/3.024 = 1.000661
        // >= 1. TickSize.roundEntry FLOORs a BUY price away from the fill, so the broker gets
        // 71.02 -- and if the controller fed THAT rounded price into detect() instead of the raw
        // one, rMultiple would recompute to 3.02/3.024 = 0.998677 < 1 and the add-on would be
        // silently dropped. This is the raw-vs-rounded split the controller comment above
        // tranche2Detector.detect(...) documents (decision raw, mechanics rounded).
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "BUY",
                new BigDecimal("68"), new BigDecimal("64.976"), new BigDecimal("64.976"),
                null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(assembler.assembleForSymbol(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("71.026"), new BigDecimal("2")));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-7", "stop-7", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controllerWithRealTranche2Detector().addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("reason")).isEqualTo(Tranche2Detector.R_CONFIRMED);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("71.02");
    }

    @Test
    void addTranche_priceRoundsDownForBuyAt70505() {
        // R_CONFIRMED so eligibility fires independent of entryDayHigh/detector wiring; the mock
        // detector is fine here since this test is only about the rounding of ctx.price() itself.
        // activeStop is intentionally NOT a round tick (55.004): a round stop makes
        // stopLossStop() inert to a mutation that swaps the rounded stop for the raw one, or that
        // rounds it in the wrong direction (roundStop -> roundTarget) -- both would be silently
        // unasserted. BUY rounds the stop CEILING (toward the entry): 55.004 -> 55.01.
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "BUY",
                new BigDecimal("60"), new BigDecimal("55"), new BigDecimal("55.004"), null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(assembler.assembleForSymbol(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("70.505"), new BigDecimal("2")));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-7", "stop-7", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        controller.addTranche(BEARER, "run-1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        // BUY rounds the order price away from the fill -> floor.
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("70.50");
        // BUY rounds the stop toward the entry -> ceiling. A live protective-stop leg: if this
        // ever regresses to the raw activeStop, or to the wrong rounding direction, Saxo's
        // per-leg rejection could leave the entry filled and the position unprotected.
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("55.01");
    }

    @Test
    void addTranche_priceRoundsUpForSellAt70505() {
        // activeStop 85.006 is intentionally NOT a round tick, for the same reason as the BUY
        // case above. SELL rounds the stop FLOOR (toward the entry): 85.006 -> 85.00.
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "SELL",
                new BigDecimal("80"), new BigDecimal("85"), new BigDecimal("85.006"), null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(assembler.assembleForSymbol(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("70.505"), new BigDecimal("2")));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-7", "stop-7", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        controller.addTranche(BEARER, "run-1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        // SELL rounds the order price away from the fill -> ceiling.
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("70.51");
        // SELL rounds the stop toward the entry -> floor. Same live-protective-stop rationale as
        // the BUY case above.
        assertThat(reqCaptor.getValue().stopLossStop()).isEqualByComparingTo("85.00");
    }

    @Test
    void addTranche_collapse_noPlaceBracket_singleNoStopDecision() {
        // Raw the pair is valid (70.504 > 70.498), but rounded independently both land on 70.50 --
        // there is no OrderGuard on this path to catch that, so the controller must reject
        // explicitly with NO_STOP rather than send a zero-width bracket.
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "BUY",
                new BigDecimal("60"), new BigDecimal("55"), new BigDecimal("70.498"), null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(assembler.assembleForSymbol(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("70.504"), new BigDecimal("2")));
        // Stubbed even though a correct implementation never calls it: without this stub, a
        // mutant that removes the collapse guard dies on an unrelated NPE (placeBracket()
        // returning null) INSIDE the try block, before the never()-verification below ever runs
        // -- a false diagnostic that happens to kill the mutant for the wrong reason. With the
        // stub, a broken guard reaches the real assertion and fails on it directly.
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-x", "stop-x", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NO_STOP");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).updateTranche2(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<ExecutorDecision> decisionCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, times(1)).insert(decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().accepted()).isFalse();
        assertThat(decisionCaptor.getValue().rejectReason()).isEqualTo("NO_STOP");
    }

    @Test
    void addTranche_roundedStopShrinksRPerShare_heatLimitNowPasses() {
        // Raw rPerShare = 50.009 - 44.991 = 5.018; qty=100 -> raw risk 501.8. Rounded
        // rPerShare = floor(50.009)=50.00 minus ceil(44.991)=45.00 = 5.00; rounded risk 500.0.
        // heatPct is tuned so the heat ceiling (501.0) sits strictly between the two: sizing off
        // the raw pair would breach it, sizing off the rounded pair (what the controller actually
        // does) passes. Direction, precisely: PER-SHARE risk (rPerShare) never grows from tick
        // rounding alone -- BUY rounds the entry down toward the stop and the stop up toward the
        // entry, both narrowing the window. TOTAL risk (rPerShare * qty) is a different claim: it
        // CAN grow, because qty = floor(trancheAmount / price) and a BUY entry that rounds down
        // can push qty up by one whole share (see
        // addTranche_qtyCanGrowByOneShare_whenRoundedEntryCrossesAFloorBoundary below, same
        // price/trancheAmount deliberately reused to make the two tests' relationship visible).
        // This test's trancheAmount (5005) is chosen so qty is unaffected by that effect (qty=100
        // both raw and rounded) -- it isolates the per-share claim only.
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "BUY",
                new BigDecimal("45"), new BigDecimal("40"), new BigDecimal("44.991"), null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(assembler.assembleForSymbol(any())).thenReturn(withTrancheAmount(
                withPriceAndAtr(happyContext(), new BigDecimal("50.009"), new BigDecimal("2")),
                new BigDecimal("5005")));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-7", "stop-7", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controllerWith(BUFFER_ONE, sizer, tranche2Detector,
                RISK_UNBOUND, 0.0501).addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(((BigDecimal) output.get("qty"))).isEqualByComparingTo("100");
        verify(gateway).placeBracket(eq("depot-1"), any());
    }

    @Test
    void addTranche_qtyCanGrowByOneShare_whenRoundedEntryCrossesAFloorBoundary() {
        // trancheAmount=5000, price=50.009: raw qty = floor(5000/50.009) = floor(99.982) = 99.
        // The BUY entry rounds DOWN (away from the fill, per TickSize.roundEntry) to 50.00, and
        // floor(5000/50.00) = 100 -- rounding the entry price can push qty across a whole-share
        // boundary and INCREASE total risk (99*5.018=496.78 raw vs 100*5.00=500.00 rounded), even
        // though per-share risk (rPerShare) never grows. Default heatPct (0.06 -> ceiling 600)
        // keeps this well clear of HEAT_LIMIT so the qty jump itself is what's under test.
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "BUY",
                new BigDecimal("45"), new BigDecimal("40"), new BigDecimal("44.991"), null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(assembler.assembleForSymbol(any())).thenReturn(withTrancheAmount(
                withPriceAndAtr(happyContext(), new BigDecimal("50.009"), new BigDecimal("2")),
                new BigDecimal("5000")));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-7", "stop-7", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controllerWith(BUFFER_ONE, sizer, tranche2Detector,
                RISK_UNBOUND, 0.06).addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        // Would be 99 if sized off the raw (unrounded) price.
        assertThat(((BigDecimal) output.get("qty"))).isEqualByComparingTo("100");
    }

    // -------------------------------------------------------------------
    // add-tranche: idempotent retry after a prior BROKER_ERROR + attempt cap.
    // Mirrors the place-entry guard at the top of this file — since Agora randomises
    // X-Request-ID per attempt, the broker no longer dedupes for us.
    // -------------------------------------------------------------------

    @Test
    void tranche2AdoptsAnExistingOrderAfterAPriorBrokerError() {
        // Szenario: der vorige Versuch erreichte den Broker (Order liegt), wurde aber als
        // unavailable gemeldet. Ohne Guard entstünde eine ZWEITE Tranche-Order — und seit
        // Agora die X-Request-ID pro Versuch würfelt, fängt der Broker das nicht mehr ab.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(1);
        when(gateway.orderByRef("depot-1", "t2-sig-1")).thenReturn(Optional.of(
                new BrokerOrder("brk-existing", "t2-sig-1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                        new BigDecimal("7"), BigDecimal.ZERO, null, null)));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        // the adopted order's own qty is booked, not the freshly re-computed sizer qty (10)
        assertThat(((BigDecimal) output.get("qty"))).isEqualByComparingTo("7");

        verify(gateway, never()).placeBracket(any(), any());

        ArgumentCaptor<BigDecimal> qtyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> entryCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateTranche2(eq(7L), qtyCaptor.capture(), entryCaptor.capture(),
                eq("brk-existing"), isNull());
        // An ADOPTED order is still only WORKING — nothing is held yet either, so the book keeps
        // the 10 shares it holds (BUG-S9).
        assertThat(qtyCaptor.getValue()).isEqualByComparingTo("10");
        assertThat(entryCaptor.getValue()).isEqualByComparingTo("100");

        verify(decisionRepo).insert(argThat(d -> d != null && !d.accepted()
                && "DUPLICATE".equals(d.rejectReason())
                && "brk-existing".equals(d.brokerOrderId())));
        verify(decisionRepo).insert(argThat(d -> d != null && d.accepted()
                && "brk-existing".equals(d.brokerOrderId())));
    }

    @Test
    void tranche2PlacesNormallyWithoutPriorBrokerErrors() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(0);
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);
        verify(gateway, never()).orderByRef(any(), any());
        verify(gateway, times(1)).placeBracket(eq("depot-1"), any(BracketRequest.class));
    }

    @Test
    void tranche2GoesTerminalAfterMaxBrokerAttempts() {
        // maxBrokerAttempts = 3 in this fixture; no live order exists under the tranche ref,
        // so there is nothing to adopt and no further placement may be attempted.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        // Lifetime count only drives the adoption lookup; the cap reads the windowed run count.
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(3);
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(3);
        when(gateway.orderByRef("depot-1", "t2-sig-1")).thenReturn(Optional.empty());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("MAX_BROKER_ATTEMPTS");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).updateTranche2(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("MAX_BROKER_ATTEMPTS");
    }

    @Test
    void addTranche_threeBrokerErrorsInOneRunDoNotTripTheCap() {
        // Exactly the STT shape: 8 BROKER_ERROR rows over the lifetime, but spread over only a
        // handful of runs — two of them inside the window. A row-counting cap trips here; a
        // run-counting cap must not.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(2);
        // Lifetime count > 0 sends the adoption guard to the broker; nothing to adopt.
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(8);
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-1")).thenReturn(0);
        when(gateway.orderByRef("depot-1", "t2-sig-1")).thenReturn(Optional.empty());
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        assertThat(outputOf(resp).get("reason")).isNotEqualTo("MAX_BROKER_ATTEMPTS");
        verify(gateway, times(1)).placeBracket(eq("depot-1"), any(BracketRequest.class));
    }

    @Test
    void addTranche_threeFailedRunsInsideTheWindowTripTheCap() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(3);
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(3);
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-1")).thenReturn(0);
        when(gateway.orderByRef("depot-1", "t2-sig-1")).thenReturn(Optional.empty());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("MAX_BROKER_ATTEMPTS");

        verify(gateway, never()).placeBracket(any(), any());
    }

    @Test
    void addTranche_capHealsOnceFailedRunsFallOutOfTheWindow() {
        // Four failed runs in total, but only two inside the window -> placement resumes. This is
        // what un-blocks a signal like STT without rewriting its audit trail.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(8);
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(2);
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-1")).thenReturn(0);
        when(gateway.orderByRef("depot-1", "t2-sig-1")).thenReturn(Optional.empty());
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), any(BracketRequest.class));
    }

    @Test
    void addTranche_throttleBlocksTheThirdBrokerCallOfTheSameRun() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-1")).thenReturn(2);
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(2);
        when(decisionRepo.countDistinctRunsByReasonSince(eq("sig-1"), eq("BROKER_ERROR"), any()))
                .thenReturn(1);
        when(gateway.orderByRef("depot-1", "t2-sig-1")).thenReturn(Optional.empty());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("BROKER_RETRY_EXHAUSTED");

        verify(gateway, never()).placeBracket(any(), any());
        verify(positionRepo, never()).updateTranche2(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(decCaptor.capture());
        assertThat(decCaptor.getValue().accepted()).isFalse();
        assertThat(decCaptor.getValue().rejectReason()).isEqualTo("BROKER_RETRY_EXHAUSTED");
    }

    @Test
    void addTranche_anAdoptableOrderIsTakenEvenWhenTheRunBudgetIsExhausted() {
        // Ordering invariant, mirrored from the entry path: adoption runs BEFORE the throttle.
        // Reversed, an order that is already live at the broker would stay without a DB
        // counterpart forever.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(2);
        when(decisionRepo.countByReasonInRun("sig-1", "BROKER_ERROR", "run-1")).thenReturn(2);
        when(gateway.orderByRef("depot-1", "t2-sig-1")).thenReturn(Optional.of(
                new BrokerOrder("brk-existing", "t2-sig-1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                        new BigDecimal("7"), BigDecimal.ZERO, null, null)));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("reason")).isNotEqualTo("BROKER_RETRY_EXHAUSTED");

        verify(gateway, never()).placeBracket(any(), any());

        ArgumentCaptor<ExecutorDecision> decCaptor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, atLeastOnce()).insert(decCaptor.capture());
        assertThat(decCaptor.getAllValues()).anyMatch(d -> "DUPLICATE".equals(d.rejectReason()));
        assertThat(decCaptor.getAllValues())
                .noneMatch(d -> "BROKER_RETRY_EXHAUSTED".equals(d.rejectReason()));
    }

    @Test
    void addTranche_positionWithoutSourceSignalStillPlacesUnconditionally() {
        // A manual/imported position has no counting axis at all — neither the throttle nor the
        // cap may fire, and neither count may even be queried.
        ExecutorPosition open = new ExecutorPosition(42L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 1, null, List.of("X"), null, "hunter",
                "2026-06-01", null, "OPEN", "brk-1", new BigDecimal("100"), null, 0,
                null, null, null, null, null, null, null, null, null, 0, null, null,
                null, null, null, null, false, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-42", "stop-42", null, "t2-pos-42", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        assertThat(outputOf(resp).get("placed")).isEqualTo(true);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), any(BracketRequest.class));
        verify(decisionRepo, never()).countByReasonInRun(any(), any(), any());
        verify(decisionRepo, never()).countDistinctRunsByReasonSince(any(), any(), any());
    }

    @Test
    void addTranche_authRejected() {
        ResponseEntity<?> resp = controller.addTranche("Bearer wrong", "run-1", json("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(gateway, positionRepo, decisionRepo, tranche2Detector);
    }

    // -------------------------------------------------------------------
    // complete
    // -------------------------------------------------------------------

    @Test
    void complete_returns204() {
        ResponseEntity<Void> resp = controller.complete(BEARER, "run-1", json("{\"status\":\"done\"}"));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void complete_authRejected() {
        ResponseEntity<Void> resp = controller.complete("Bearer wrong", "run-1", json("{\"status\":\"done\"}"));
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // -------------------------------------------------------------------
    // entryExpiry — weekend-skip GTD math
    // -------------------------------------------------------------------

    @Test
    void entryExpiry_noWeekendInWindow_addsCalendarDaysOnly() {
        // Wednesday 2026-07-01 + 2 days = Friday 2026-07-03, no roll.
        Instant now = Instant.parse("2026-07-01T09:00:00Z");
        assertThat(ExecutorWebhookController.entryExpiry(now, 2))
                .isEqualTo(Instant.parse("2026-07-03T09:00:00Z"));
    }

    @Test
    void entryExpiry_landsOnSaturday_rollsToMonday() {
        // Thursday 2026-07-02 + 2 days = Saturday 2026-07-04 -> rolls to Monday 2026-07-06.
        Instant now = Instant.parse("2026-07-02T09:00:00Z");
        assertThat(ExecutorWebhookController.entryExpiry(now, 2))
                .isEqualTo(Instant.parse("2026-07-06T09:00:00Z"));
    }

    @Test
    void entryExpiry_landsOnSunday_rollsToMonday() {
        // Friday 2026-07-03 + 2 days = Sunday 2026-07-05 -> rolls to Monday 2026-07-06.
        Instant now = Instant.parse("2026-07-03T09:00:00Z");
        assertThat(ExecutorWebhookController.entryExpiry(now, 2))
                .isEqualTo(Instant.parse("2026-07-06T09:00:00Z"));
    }

    // -------------------------------------------------------------------
    // SP1: buffered broker stop, risk-capped sizing, atrEff in lockstep
    // -------------------------------------------------------------------

    /** Test 31. The bracket carries the BUFFERED stop; the book carries the LOGICAL one.
     *  Mutation: send stopPrice to the bracket, or persist the broker stop as active_stop. */
    @Test
    void bracketStopIsBufferedBelowLogicalStopWhilePositionStoresLogical() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":95}
                """));

        ArgumentCaptor<BracketRequest> req = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), req.capture());
        // logical stop 95.00, atrEff 2, buffer 1.0 -> 93.00
        assertThat(req.getValue().stopLossStop()).isEqualByComparingTo("93.00");

        ArgumentCaptor<ExecutorPosition> pos = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(pos.capture());
        assertThat(pos.getValue().initialStop()).isEqualByComparingTo("95.00");
        assertThat(pos.getValue().activeStop()).isEqualByComparingTo("95.00");
        assertThat(pos.getValue().brokerStop()).isEqualByComparingTo("93.00");
        assertThat(pos.getValue().entryFilledAt()).isNull();
    }

    /** Test 31b. The sizer never sees the broker stop and nothing feeds it back: sizing runs ONCE,
     *  on the logical stop. Mutation: re-run sizer.size on the buffered stop (which would report a
     *  larger r_per_share and a smaller qty than the book's own risk). */
    @Test
    void brokerStopIsNotFedBackIntoSizing() {
        PositionSizer spySizer = spy(new PositionSizer());
        ExecutorWebhookController c = controllerWith(BUFFER_ONE, spySizer, tranche2Detector);
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        c.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":95}
                """));

        ArgumentCaptor<BigDecimal> stopArg = ArgumentCaptor.forClass(BigDecimal.class);
        verify(spySizer, times(1)).size(eq("BUY"), any(), any(), any(), stopArg.capture(),
                any(), any(), any(), any());
        assertThat(stopArg.getValue()).isEqualByComparingTo("95.00");

        DecisionLog log = enterLog();
        assertThat(log.orderJson().path("r_per_share").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("5.00"));
        assertThat(log.orderJson().path("position_risk").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("50"));
    }

    /** Test 31c. A zero ATR collapses the window onto the entry, the clamp puts the stop AT the
     *  price, and rPerShare is zero. That is a NO_STOP, exactly as before SP1 (where OrderGuard
     *  produced it) — not a TRANCHE_TOO_SMALL.
     *  Mutation: route every zero qty through the TRANCHE_TOO_SMALL handler. */
    @Test
    void zeroAtrStillRejectsWithNoStopNotTrancheTooSmall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("100"), BigDecimal.ZERO));

        ResponseEntity<Map<String, Object>> res = controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":95}
                """));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.getBody().get("output");
        assertThat(out.get("placed")).isEqualTo(false);
        assertThat(out.get("reason")).isEqualTo("NO_STOP");
        verify(gateway, never()).placeBracket(any(), any());
    }

    /** Test 34e. Stop distance in account currency exceeds the whole 1 % risk budget -> the new
     *  terminal RISK_TOO_WIDE, and the signal is REJECTED, not left PENDING.
     *  Mutation: route through the TRANCHE_TOO_SMALL handler. */
    @Test
    void riskCapZeroRejectsWithRiskTooWide() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("500")));
        // price 500, atr 60 -> window [305, 350]; a 380 proposal clamps to 350, r/share 150.
        // risk budget = 10000 * 0.01 = 100 -> floor(100/150) = 0. qtyNotional = floor(1000/500) = 2.
        when(assembler.assemble(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("500"), new BigDecimal("60")));

        ResponseEntity<Map<String, Object>> res = controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":500,"stop_price":380}
                """));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.getBody().get("output");
        assertThat(out.get("reason")).isEqualTo("RISK_TOO_WIDE");
        verify(signalRepo).markStatus("sig-1", "REJECTED");
        verify(gateway, never()).placeBracket(any(), any());
    }

    /** Test 32. With the buffer at zero the bracket gets the logical stop VERBATIM — including the
     *  un-tick-rounded value StopWindowRounding's degenerate branch produces. This is the
     *  mechanical proof the feature is additive.
     *  Mutation: any offset, or any re-rounding, on the identity path. */
    @Test
    void bufferZeroReproducesLegacyBracketIncludingDegenerateWindow() {
        ExecutorWebhookController c = controllerWith(BigDecimal.ZERO, sizer, tranche2Detector);
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100.00")));
        // atr 0.006 -> raw window [99.98050, 99.985]; rounded inward it inverts (99.99 > 99.98),
        // so StopWindowRounding skips tick rounding entirely and clamps the raw proposal into the
        // RAW window: 99.00 -> 99.98050, an un-tick-rounded value.
        when(assembler.assemble(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("100.00"), new BigDecimal("0.006")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        c.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100.00,"stop_price":99.00}
                """));

        ArgumentCaptor<BracketRequest> req = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), req.capture());
        assertThat(req.getValue().stopLossStop()).isEqualByComparingTo("99.9805");
        assertThat(req.getValue().stopLossStop().stripTrailingZeros().scale())
                .as("the degenerate branch's un-tick-rounded value must reach the broker verbatim")
                .isGreaterThan(2);
    }

    /** Test 34b. The window, the clamp and the sizing call must all receive the SAME ATR — atrEff.
     *  Mutation: pass ctx.atr() at :645, :655 or :661. With atr 2 and atrShort 4 the two ATRs give
     *  windows that do not overlap on the clamp target, so every one of the three sites is pinned.
     *  (Mirrors placeEntry_stopWindowRule2Regression_buy, which pins the price the same way.) */
    @Test
    void placeEntryUsesTheSameAtrForWindowClampAndSizing() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any())).thenReturn(withPriceAtrAndShort(happyContext(),
                new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("4")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":95}
                """));

        // atrEff 4 -> window [87.0, 90.0], the 95 proposal clamps to 90.00 (:645 + :655).
        ArgumentCaptor<ExecutorPosition> pos = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(pos.capture());
        assertThat(pos.getValue().activeStop()).isEqualByComparingTo("90.00");

        DecisionLog log = enterLog();
        // :645 — the raw window written to order_json
        assertThat(log.orderJson().path("stop_min").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("87.0"));
        assertThat(log.orderJson().path("stop_max").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("90.0"));
        // :661 — r_per_share follows the clamped stop, 100 - 90 = 10, not 100 - 95 = 5
        assertThat(log.orderJson().path("r_per_share").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("10.00"));
        // :661 — the SIZER's own window verdict. The clamped stop 90.00 sits inside the atrEff
        // window [87.0, 90.0] but far below the ATR22 window [93.5, 95.0], so a sizer handed
        // ctx.atr() reports stop_in_window=false. This is the assertion that reddens the
        // sizer-site mutation; stop_min/stop_max above only pin the window site.
        assertThat(log.orderJson().path("stop_in_window").asBoolean())
                .as("the sizer must judge the stop against the SAME atrEff window")
                .isTrue();
        // the atr snapshot itself stays ATR22
        assertThat(log.inputsSnapshot().path("atr").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("2"));
    }

    /** Test 34c. fetch-pending-signals shows the LLM the window derived from atrEff, while the
     *  `atr` field it reports stays ATR22 — the LLM proposes stops inside the wider window but
     *  reasons about the volatility number it has always seen.
     *  Mutation: atrEff -> atr at :309, or atr -> atrEff at :305. */
    @Test
    void fetchPendingSignalsWindowUsesAtrEffWhileAtrFieldStaysAtr22() {
        ExecutorSignal s = new ExecutorSignal("sig-1", "hunter", "v1", "ACME", "BUY",
                0.9, "mechanism", List.of("X"), "3m", new BigDecimal("100"), "PENDING",
                "2026-07-01T00:00:00Z");
        when(signalRepo.findPending(50)).thenReturn(List.of(s));
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(executorIndicators.levels(eq("ACME"), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2"), null,
                        new BigDecimal("100"), new BigDecimal("4")));

        ResponseEntity<Map<String, Object>> res = controller.fetchPendingSignals(BEARER, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.getBody().get("output");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) out.get("signals");
        assertThat(signals).hasSize(1);
        assertThat((BigDecimal) signals.get(0).get("atr")).isEqualByComparingTo("2");
        // window from atrEff 4: [100 - 12 - 1, 100 - 10] = [87.0, 90.0]
        assertThat((BigDecimal) signals.get(0).get("stop_min")).isEqualByComparingTo("87.0");
        assertThat((BigDecimal) signals.get(0).get("stop_max")).isEqualByComparingTo("90.0");
    }

    /** Test 34d. stop_basis names the ATR that actually produced the window.
     *  Mutation: keep the hard-coded "ATR22" string in PositionSizer. */
    @Test
    void orderJsonStopBasisNamesTheAtrActuallyUsed() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any())).thenReturn(withPriceAtrAndShort(happyContext(),
                new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("4")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":95}
                """));

        assertThat(enterLog().orderJson().path("stop_basis").asString())
                .isEqualTo("entry - 2.5 x ATR5");
    }

    /** Test 34d, the ATR22 half: with no short ATR the label is the long window's. */
    @Test
    void orderJsonStopBasisNamesAtr22WhenNoShortAtr() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":95}
                """));

        assertThat(enterLog().orderJson().path("stop_basis").asString())
                .isEqualTo("entry - 2.5 x ATR22");
    }

    /** Test 34, entry half. Every new audit key is present and carries the right value.
     *  Mutation: drop any one of them, or compute position_risk_broker from the logical stop. */
    @Test
    void orderJsonCarriesBrokerStopAtrFieldsQtyRiskSizingBasisBothRisks() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(assembler.assemble(any())).thenReturn(withPriceAtrAndShort(happyContext(),
                new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("2")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":95}
                """));

        JsonNode oj = enterLog().orderJson();
        for (String key : List.of("broker_stop", "broker_stop_buffer_atr", "broker_stop_clamped",
                "broker_stop_capped", "qty_notional", "qty_risk", "sizing_basis", "reject_cause",
                "risk_pct", "position_risk", "position_risk_broker", "atr_short", "atr_effective",
                "stop_basis")) {
            assertThat(oj.has(key)).as("missing order_json key " + key).isTrue();
        }
        assertThat(oj.path("broker_stop").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("93.00"));
        assertThat(oj.path("broker_stop_buffer_atr").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ONE);
        assertThat(oj.path("broker_stop_clamped").asBoolean()).isFalse();
        assertThat(oj.path("broker_stop_capped").asBoolean()).isFalse();
        assertThat(oj.path("qty_notional").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("10"));
        assertThat(oj.path("sizing_basis").asString()).isEqualTo("NOTIONAL");
        assertThat(oj.path("reject_cause").isNull()).isTrue();
        assertThat(oj.path("risk_pct").asDouble()).isEqualTo(0.01);
        assertThat(oj.path("atr_short").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("2"));
        assertThat(oj.path("atr_effective").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("2"));
        // position_risk stays on the LOGICAL stop: 10 * (100 - 95) * 1
        assertThat(oj.path("position_risk").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("50"));
        // position_risk_broker is what the RESTING LEG permits: 10 * (100 - 93) * 1
        assertThat(oj.path("position_risk_broker").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("70"));
    }

    /** The open-positions payload gains broker_stop and atr_short; active_stop keeps mirroring the
     *  LOGICAL stop deliberately (proximity alerts watch the level that decides).
     *  Mutation: put the broker stop into active_stop. */
    @Test
    void fetchOpenPositionsPayloadCarriesBrokerStopAndAtrShort() {
        EnrichedPosition ep = new EnrichedPosition(1L, "depot-1", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("101"), new BigDecimal("2"), new BigDecimal("95"),
                new BigDecimal("1.0"), new BigDecimal("1.0"), 3, List.of("X"), List.of(),
                false, false, 0, false, null, "sig-1", 0, 0.33, true,
                new BigDecimal("4"), new BigDecimal("93.00"));
        when(pipeline.run(eq("depot-1"), any())).thenReturn(List.of(ep));

        ResponseEntity<Map<String, Object>> res = controller.fetchOpenPositions(BEARER, "run-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.getBody().get("output");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> positions = (List<Map<String, Object>>) out.get("positions");
        assertThat((BigDecimal) positions.get(0).get("active_stop")).isEqualByComparingTo("95");
        assertThat((BigDecimal) positions.get(0).get("broker_stop")).isEqualByComparingTo("93.00");
        assertThat((BigDecimal) positions.get(0).get("atr_short")).isEqualByComparingTo("4");
    }

    /** Test 33. The PLACEMENT path, not only the detector: a position the broker has not filled
     *  cannot get a second tranche. Uses the REAL Tranche2Detector, because the fill precondition
     *  lives there and the shared mock would stub it away.
     *  Mutation: drop the entryFilledAt check from the detector, or pass the detector a position
     *  without it. */
    @Test
    void addTrancheRejectsUnfilledEntry() {
        ExecutorWebhookController c = controllerWith(BUFFER_ONE, sizer, new Tranche2Detector());
        // +1R and above entry: R_CONFIRMED would fire if the entry were filled.
        ExecutorPosition unfilled = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("90"));   // entryFilledAt is null in this fixture
        when(positionRepo.findOpen()).thenReturn(List.of(unfilled));
        when(assembler.assembleForSymbol("ACME"))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("110"), new BigDecimal("2")));

        ResponseEntity<Map<String, Object>> res = c.addTranche(BEARER, "run-1",
                json("{\"symbol\":\"ACME\"}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.getBody().get("output");
        assertThat(out.get("placed")).isEqualTo(false);
        assertThat(out.get("reason")).isEqualTo("NOT_ELIGIBLE");
        verify(gateway, never()).placeBracket(any(), any());
    }

    /** Test 34, add-tranche half. The tranche add now writes a decision_log row with the SAME
     *  order_json keys as place-entry, an inputs_snapshot from the same helper, and a synthesised
     *  BUDGET/HEAT_LIMIT veto_results pair — so the audit query has no tranche-2 hole.
     *
     *  <p>The action is ADD_TRANCHE, not the ENTER spec §2.1 asked for: the row carries the SAME
     *  signal_id as the entry, and under ENTER it would shadow the entry row for every
     *  "the ENTER row of this signal" lookup (see
     *  {@code DecisionLogRepositoryTest.addTrancheRowDoesNotShadowTheEntryRow}).
     *
     *  Mutation: drop the write, write ENTER, or omit any of the keys. */
    @Test
    void addTrancheWritesADecisionLogRowWithTheSameOrderJson() {
        ExecutorWebhookController c = controllerWith(BUFFER_ONE, sizer, new Tranche2Detector());
        ExecutorPosition filled = filledPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("90"), "2026-07-02T00:00:00Z");
        when(positionRepo.findOpen()).thenReturn(List.of(filled));
        when(assembler.assembleForSymbol("ACME"))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("110"), new BigDecimal("2")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", null, "t2-sig-1", OrderStatus.WORKING));

        c.addTranche(BEARER, "run-1", json("{\"symbol\":\"ACME\"}"));

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        DecisionLog log = captor.getValue();

        assertThat(log.triggerType()).isEqualTo("SIGNAL");
        assertThat(log.action()).isEqualTo("ADD_TRANCHE");
        assertThat(log.reasonCode()).isNull();
        assertThat(log.signalId()).isEqualTo("sig-1");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.runId()).isEqualTo("run-1");
        assertThat(log.inputsSnapshot()).isNotNull();
        for (String key : List.of("broker_stop", "qty_notional", "qty_risk", "sizing_basis",
                "reject_cause", "position_risk", "position_risk_broker", "atr_short",
                "atr_effective", "stop_basis")) {
            assertThat(log.orderJson().has(key)).as("missing order_json key " + key).isTrue();
        }
        assertThat(log.vetoResults()).hasSize(2);
        assertThat(log.vetoResults().get(0).get("check").asString()).isEqualTo("BUDGET");
        assertThat(log.vetoResults().get(1).get("check").asString()).isEqualTo("HEAT_LIMIT");
    }

    /** The tranche bracket carries the buffered stop too — a second leg resting at the logical
     *  stop would still be wick-sensitive.
     *  Mutation: send position.activeStop() (tick-rounded) to the tranche bracket. */
    @Test
    void addTrancheBracketCarriesTheBufferedStop() {
        ExecutorWebhookController c = controllerWith(BUFFER_ONE, sizer, new Tranche2Detector());
        ExecutorPosition filled = filledPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("90"), "2026-07-02T00:00:00Z");
        when(positionRepo.findOpen()).thenReturn(List.of(filled));
        when(assembler.assembleForSymbol("ACME"))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("110"), new BigDecimal("2")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", null, "t2-sig-1", OrderStatus.WORKING));

        c.addTranche(BEARER, "run-1", json("{\"symbol\":\"ACME\"}"));

        ArgumentCaptor<BracketRequest> req = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), req.capture());
        // the position's active stop 90 rounds to 90.00, buffered by 1 x atrEff 2 -> 88.00
        assertThat(req.getValue().stopLossStop()).isEqualByComparingTo("88.00");
        assertThat(req.getValue().takeProfitLimit()).isNull();
    }

    /** The Saxo proximity band is the reason BrokerStop has rule 3 at all: a leg outside it comes
     *  back as TooFarFromEntryOrder and takes the whole bracket with it. Nothing else in the suite
     *  observes the cap binding, so a null/disabled band would pass unnoticed.
     *  Mutation: pass null (or a wrong pct) as maxBrokerStopPct, or stop reporting `capped`. */
    @Test
    void entryProximityCapBindsAndIsFlagged() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        // price 100, atr 7 -> window [77.25, 82.5]; the 82 proposal needs no clamping. The logical
        // stop 82 is still INSIDE the 20 % band (bound 80.00), but 82 - 1 x atrEff 7 = 75 is not,
        // so the buffer is cut back to the band edge rather than shrunk away entirely.
        when(assembler.assemble(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("100"), new BigDecimal("7")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        controller.placeEntry(BEARER, null, json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":100,"stop_price":82}
                """));

        ArgumentCaptor<BracketRequest> req = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), req.capture());
        // entry x (1 - 0.20), tick-rounded toward the entry -- NOT the unbanded 75.00.
        assertThat(req.getValue().stopLossStop()).isEqualByComparingTo("80.00");

        JsonNode oj = enterLog().orderJson();
        assertThat(oj.path("broker_stop_capped").asBoolean())
                .as("a cap that binds must be auditable").isTrue();
        assertThat(oj.path("broker_stop_clamped").asBoolean()).isFalse();
        // The audit value and the wire value are one number, computed once.
        assertThat(oj.path("broker_stop").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(req.getValue().stopLossStop());
        // The book still records the LOGICAL stop; only the resting leg was pulled in.
        ArgumentCaptor<ExecutorPosition> pos = ArgumentCaptor.forClass(ExecutorPosition.class);
        verify(positionRepo).insert(pos.capture());
        assertThat(pos.getValue().activeStop()).isEqualByComparingTo("82.00");
    }

    /** The add-tranche sibling of {@link #entryProximityCapBindsAndIsFlagged}: the second leg is a
     *  bracket too, and Saxo bands it the same way.
     *  Mutation: pass null as maxBrokerStopPct at the add-tranche BrokerStop.forEntry call. */
    @Test
    void addTrancheProximityCapBindsAndIsFlagged() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("82"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(assembler.assembleForSymbol(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("100"), new BigDecimal("7")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-2", "stop-2", null, "t2-sig-1", OrderStatus.WORKING));

        controller.addTranche(BEARER, "run-1", json("{\"symbol\":\"ACME\"}"));

        ArgumentCaptor<BracketRequest> req = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), req.capture());
        assertThat(req.getValue().stopLossStop()).isEqualByComparingTo("80.00");

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(captor.capture());
        JsonNode oj = captor.getValue().orderJson();
        assertThat(oj.path("broker_stop_capped").asBoolean()).isTrue();
        assertThat(oj.path("broker_stop").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(req.getValue().stopLossStop());
        // stop_price stays the position's own (tick-rounded) logical stop.
        assertThat(oj.path("stop_price").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("82.00"));
    }
}
