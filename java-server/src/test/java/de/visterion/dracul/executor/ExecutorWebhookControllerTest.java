package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.executor.broker.PlacedBracket;
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

    private ExecutorSignalRepository signalRepo;
    private ExecutorPositionRepository positionRepo;
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

        controller = new ExecutorWebhookController(
                signalRepo, positionRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, sizer, ranker, tranche2Detector, telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD", fixedClock);
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
                "USD");
    }

    private static EntryContext withMissing(EntryContext c, List<String> missing) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                missing, c.quoteCurrency());
    }

    private static EntryContext withOpenPositions(EntryContext c, List<ExecutorPosition> positions) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), positions, c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
    }

    private static EntryContext withEntriesThisWeek(EntryContext c, int entriesThisWeek) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), entriesThisWeek, c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
    }

    private static EntryContext withPendingSignals(EntryContext c, List<ExecutorSignal> pending) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                pending, c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
    }

    private static EntryContext withSignalAge(EntryContext c, long ageTradingDays) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), ageTradingDays, c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
    }

    private static EntryContext withTrancheAmount(EntryContext c, BigDecimal trancheAmount) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), trancheAmount,
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
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
                withTranche.fxToAccount(), withTranche.missing(), withTranche.quoteCurrency());
    }

    private static EntryContext withPrice(EntryContext c, BigDecimal price) {
        return new EntryContext(c.account(), price, c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
    }

    private static EntryContext withPriceAndAtr(EntryContext c, BigDecimal price, BigDecimal atr) {
        return new EntryContext(c.account(), price, atr, c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
    }

    private static EntryContext withOpenHeat(EntryContext c, BigDecimal openHeat) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), openHeat, c.openMechanisms(), c.fxToAccount(),
                c.missing(), c.quoteCurrency());
    }

    private static EntryContext unavailableContext() {
        return new EntryContext(null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), 0, -1L, null, null, null, null,
                Map.of(), BigDecimal.ONE, List.of("price", "atr"), "USD");
    }

    private ExecutorPosition openPosition(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop) {
        return new ExecutorPosition(id, "depot-1", symbol, side, new BigDecimal("10"),
                entry, initialStop, initialStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null);
    }

    /** Same fixture as {@link #openPosition} but with an explicit {@code qty} and
     *  {@code trimCount} for scale-out/ladder tests. */
    private ExecutorPosition openPosition(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop, BigDecimal qty, int trimCount) {
        return new ExecutorPosition(id, "depot-1", symbol, side, qty,
                entry, initialStop, initialStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null, null,
                null, null, null, null, trimCount, null, null, null, null, null, null);
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
                signalRepo, positionRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, customSizer, ranker, tranche2Detector, telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD", fixedClock);
    }

    /** Builds a controller identical to {@link #controller} but with a lower LIQUIDITY min-price
     *  floor — needed for sub-$5 fixtures (e.g. the degenerate-window regression case, which is
     *  anchored to the empirically-verified BUY 1.50/ATR 0.03/swingLow 1.399 constants and cannot
     *  be rescaled without changing whether the window is actually degenerate). */
    private ExecutorWebhookController controllerWithMinPrice(BigDecimal minPrice) {
        return new ExecutorWebhookController(
                signalRepo, positionRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, sizer, ranker, tranche2Detector, telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, minPrice, 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD", fixedClock);
    }

    /** Builds a controller identical to {@link #controller} but wired with the REAL
     *  {@link Tranche2Detector} instead of the mock — needed to exercise the actual
     *  {@code entryDayHigh} comparison (the field-level mock always returns whatever the test
     *  stubs, which cannot prove the controller passes the RAW price into it). */
    private ExecutorWebhookController controllerWithRealTranche2Detector() {
        return new ExecutorWebhookController(
                signalRepo, positionRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, sizer, ranker, new Tranche2Detector(), telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD", fixedClock);
    }

    /** Builds a controller identical to {@link #controller} but with a caller-supplied
     *  {@code heatPct}, so a HEAT_LIMIT boundary can be placed exactly between the raw and the
     *  tick-rounded {@code newRiskAccountCcy} for a given price/stop pair. */
    private ExecutorWebhookController controllerWithHeatPct(double heatPct) {
        return new ExecutorWebhookController(
                signalRepo, positionRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, sizer, ranker, tranche2Detector, telegram, executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, heatPct, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 72, 2, 0.0, 3.0, "USD", fixedClock);
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
                /* pendingExitReason */ null, /* exitOrderId */ null, /* pendingExitFillPrice */ null);
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
        when(brokenSizer.size(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Sizing(new BigDecimal("10"), new BigDecimal("5"),
                        new BigDecimal("50"), null, null, true, "broken"));

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
        when(brokenSizer.size(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Sizing(new BigDecimal("10"), new BigDecimal("5"),
                        new BigDecimal("50"), null, null, true, "broken"));

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

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-1", body);

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

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-2", body);

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

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-3", body);

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

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-sell-1", body);

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

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-4", body);

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
        assertThat(req.stopLossStop()).isEqualByComparingTo("95");
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
        assertThat(req.stopLossStop()).isEqualByComparingTo("95");
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

        controller.placeEntry(BEARER, null, body);

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

        controller.placeEntry(BEARER, null, body);

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

            controller.placeEntry(BEARER, null, body);

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

        controller.placeEntry(BEARER, "run-5fields", body);

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

        controller.placeEntry(BEARER, null, body);

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
        when(brokenSizer.size(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Sizing(new BigDecimal("10"), new BigDecimal("6"),
                        new BigDecimal("60"), null, null, true, "broken"));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        // 94.005 is not on the 0.01 grid; BUY rounds a stop CEILING -> 94.01.
        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":94.005}
                """);

        ResponseEntity<?> resp = controllerWithSizer(brokenSizer).placeEntry(BEARER, null, body);

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
        assertThat(order.path("stop_min").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max").asDouble()).isEqualTo(95.0);
        assertThat(order.path("stop_min_rounded").asDouble()).isEqualTo(93.5);
        assertThat(order.path("stop_max_rounded").asDouble()).isEqualTo(95.0);
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
                base.openHeat(), base.openMechanisms(), base.fxToAccount(), base.missing(), base.quoteCurrency());
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

        controller.placeEntry(BEARER, "run-proposed-stop", body);

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

        controller.placeEntry(BEARER, "run-clamped", body);

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
                 "input":{"signal_id":"s1","symbol":"MRVL","side":"BUY","stop_price":95}}
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
        assertThat(req.stopLossStop()).isEqualByComparingTo("95");
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
                new ExecutorIndicators.Levels(true, new BigDecimal("2.5"), new BigDecimal("92"), new BigDecimal("100")));

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
                new ExecutorIndicators.Levels(true, new BigDecimal("2.5"), new BigDecimal("92"), new BigDecimal("100")));

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
                true, false, 1, true, "R_CONFIRMED", "sig-42", 0, 0.33, true);
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
                false, false, 0, false, null, "sig-42", 0, 0.33, false);
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
                false, false, 1, false, null, "sig-42", 0, 0.33, true);
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
                false, false, 1, false, null, "sig-42", 0, 0.33, true);
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
                position.pendingExitFillPrice());
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
                false, false, 1, false, null, "sig-42", 0, 0.33, true);
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
                false, false, 1, false, null, "sig1", 0, 0.33, true);
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
                false, false, 1, false, null, "sig-1", 0, 0.33, true);
        EnrichedPosition sellWithStop = new EnrichedPosition(2L, "depot-1", "SHRT", "SELL",
                new BigDecimal("10"), new BigDecimal("40"), new BigDecimal("50"),
                new BigDecimal("38"), new BigDecimal("2.0"), new BigDecimal("42"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of(),
                false, false, 1, false, null, "sig-2", 0, 0.33, true);
        EnrichedPosition buyWithNullStop = new EnrichedPosition(3L, "depot-1", "NOPX", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), null,
                new BigDecimal("105"), new BigDecimal("2.0"), new BigDecimal("101"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"), List.of(),
                false, false, 1, false, null, "sig-3", 0, 0.33, true);
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
                "2026-07-03T00:00:42Z", null, null, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(unfilled));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NOT_FILLED");

        verifyNoInteractions(gateway);
        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
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
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1"));

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
        verify(positionRepo).close(eq(7L), exitPriceCaptor.capture(), realizedRCaptor.capture(),
                eq("SOFT_CHANDELIER"), eq("FILL"));
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("112");
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("2.4");

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
    void exitPosition_fullExitWithoutFillPrice_stampsPendingExitInsteadOfClosing() {
        // Verified prod incident (PSMT): a flatten that is merely accepted (no avgFillPrice yet)
        // must not be booked as closed here — that books a wrong exit price/R and can mismatch
        // the broker's still-working exit order. Stamp pending and let ReconcileService finalize.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, null, "close-9"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat(output.get("pending")).isEqualTo(true);

        verify(positionRepo).markPendingExit(eq(7L), eq("SOFT_CHANDELIER"), eq("close-9"),
                isNull(), eq(FIXED_NOW));
        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).close(anyLong(), any(), any(), any(), any());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());
    }

    @Test
    void exitPosition_vistierieEnvelope_fullExit() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"run_id":"r1","tool_name":"exit_position",
                 "input":{"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "r1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);
        assertThat(output.get("exit_reason")).isEqualTo("SOFT_CHANDELIER");

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE));
        verify(positionRepo).close(eq(7L), any(), any(), eq("SOFT_CHANDELIER"), eq("FILL"));

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
        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
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

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());

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
        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());
    }

    @Test
    void exitPosition_trim033_freshPosition_scalesOutAndBumpsLadder() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenReturn(new CloseResult(new BigDecimal("3"), new BigDecimal("7"), new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33)));
        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        // qty 10 * (1-0.33) = 6.7, floored to whole shares -> 6
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("6")), eq(1));
        verify(cooldownRepo, never()).add(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("TRIM");
        assertThat(log.reasonCode()).isNull();
        assertThat(log.orderJson().path("fraction").asDouble()).isEqualTo(0.33);
        assertThat(log.orderJson().has("qty_closed")).isTrue();
        assertThat(log.orderJson().path("qty_remaining").asDouble()).isEqualTo(6.0);
    }

    @Test
    void exitPosition_trim033_roundHundredQty_noDoubleComplementDrift() {
        // Regression: 1 - 0.33 computed in primitive double is 0.6699999999999999, which floors
        // qty=100 to 66 instead of 67. The complement must be computed in BigDecimal so qty=100
        // trims to exactly remaining 67 / closed 33.
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("100"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenReturn(new CloseResult(new BigDecimal("33"), new BigDecimal("67"), new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(false);
        assertThat((BigDecimal) output.get("qty_remaining")).isEqualByComparingTo("67");
        assertThat((BigDecimal) output.get("qty_closed")).isEqualByComparingTo("33");

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33)));
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("67")), eq(1));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().orderJson().path("qty_remaining").asDouble()).isEqualTo(67.0);
        assertThat(logCaptor.getValue().orderJson().path("qty_closed").asDouble()).isEqualTo(33.0);
    }

    @Test
    void exitPosition_trim033_qty200_remaining134() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("200"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.valueOf(0.33))))
                .thenReturn(new CloseResult(new BigDecimal("66"), new BigDecimal("134"), new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.33}
                """);

        controller.exitPosition(BEARER, "run-1", body);

        // 200 * 0.67 = 134 exactly; double-complement drift would have produced 133.
        verify(positionRepo).recordTrim(eq(7L), eq(new BigDecimal("134")), eq(1));
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
        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
    }

    @Test
    void exitPosition_fraction1_explicit_fullExitPathUnchanged() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("10"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","fraction":1.0}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE));
        verify(positionRepo).close(eq(7L), any(), any(), eq("SOFT_CHANDELIER"), eq("FILL"));
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
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
                .thenReturn(new CloseResult(new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SCALE_OUT","fraction":0.5}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);

        verify(gateway, times(1)).flatten(eq("depot-1"), eq("ACME"), eq(BigDecimal.ONE));
        verify(gateway, never()).flatten(any(), any(), eq(BigDecimal.valueOf(0.5)));
        verify(positionRepo).close(eq(7L), any(), any(), eq("SCALE_OUT"), eq("FILL"));
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt());
        verify(cooldownRepo).add(eq("ACME"), eq("SCALE_OUT"), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("EXIT_FULL");
    }

    // -------------------------------------------------------------------
    // add-tranche
    // -------------------------------------------------------------------

    @Test
    void addTranche_eligible_nonDegenerateWeightedAverage() {
        // 10@100 existing + 7@102 add -> weighted-average entry (10*100 + 7*102) / 17 = 100.823529.
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
                Map.of(), BigDecimal.ONE, List.of(), "USD");
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
        assertThat(qtyCaptor.getValue()).isEqualByComparingTo("17");
        assertThat(entryCaptor.getValue()).isEqualByComparingTo("100.823529");
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
        assertThat(req.stopLossStop()).isEqualByComparingTo("95");
        assertThat(req.clientRef()).isEqualTo("t2-sig-1");

        ArgumentCaptor<BigDecimal> qtyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> entryCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateTranche2(eq(7L), qtyCaptor.capture(), entryCaptor.capture(),
                eq("brk-2"), eq("stop-2"));
        assertThat(qtyCaptor.getValue()).isEqualByComparingTo("20");
        // weighted average: (10*100 + 10*100) / 20 = 100.000000
        assertThat(entryCaptor.getValue()).isEqualByComparingTo("100.000000");

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
        assertThat(req.stopLossStop()).isEqualByComparingTo("95");
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
                null, null, null, null);
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
                null, null, null, null);
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
        // entryDayHigh=70.50, ctx.price()=70.504: the REAL Tranche2Detector's strict
        // compareTo(entryDayHigh) > 0 only fires on the raw price (70.504 > 70.50). If the
        // controller rounded ctx.price() before calling detect(), the comparison would become
        // 70.50 > 70.50 = false and the add-on would be silently dropped -- this is the gate for
        // constraint 1 in the task brief. entryPrice/initialStop are chosen so R_CONFIRMED (which
        // is checked before NEW_HIGH and would otherwise mask the entryDayHigh path) does NOT
        // fire: rMultiple = (70.504-68)/(68-65) = 0.835 < 1.
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "BUY",
                new BigDecimal("68"), new BigDecimal("65"), new BigDecimal("65"),
                new BigDecimal("70.50"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(assembler.assembleForSymbol(any()))
                .thenReturn(withPriceAndAtr(happyContext(), new BigDecimal("70.504"), new BigDecimal("2")));
        when(gateway.placeBracket(eq("depot-1"), any()))
                .thenReturn(new PlacedBracket("brk-7", "stop-7", null, "t2-sig-1", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controllerWithRealTranche2Detector().addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(output.get("reason")).isEqualTo(Tranche2Detector.NEW_HIGH);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().limitPrice()).isEqualByComparingTo("70.50");
    }

    @Test
    void addTranche_priceRoundsDownForBuyAt70505() {
        // R_CONFIRMED so eligibility fires independent of entryDayHigh/detector wiring; the mock
        // detector is fine here since this test is only about the rounding of ctx.price() itself.
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "BUY",
                new BigDecimal("60"), new BigDecimal("55"), new BigDecimal("55"), null);
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
    }

    @Test
    void addTranche_priceRoundsUpForSellAt70505() {
        ExecutorPosition open = positionWithEntryDayHighAndActiveStop(7L, "ACME", "SELL",
                new BigDecimal("80"), new BigDecimal("85"), new BigDecimal("85"), null);
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
        // does) passes. Direction: rounding SHRINKS risk here (BUY: entry rounds down toward the
        // stop, stop rounds up toward the entry -- both directions narrow the window), so
        // HEAT_LIMIT/BUDGET become easier to pass, never harder, from tick rounding alone.
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

        ResponseEntity<?> resp = controllerWithHeatPct(0.0501).addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        assertThat(((BigDecimal) output.get("qty"))).isEqualByComparingTo("100");
        verify(gateway).placeBracket(eq("depot-1"), any());
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
        assertThat(qtyCaptor.getValue()).isEqualByComparingTo("17");
        assertThat(entryCaptor.getValue()).isEqualByComparingTo("100.000000");

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
                null, null, null, null);
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
}
