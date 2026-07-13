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
    private PositionContextRepository positionContextRepo;
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
        positionContextRepo = mock(PositionContextRepository.class);
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
                assembler, sizer, ranker, tranche2Detector, telegram, positionContextRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, fixedClock);
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
                List.of());
    }

    private static EntryContext withMissing(EntryContext c, List<String> missing) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                missing);
    }

    private static EntryContext withOpenPositions(EntryContext c, List<ExecutorPosition> positions) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), positions, c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing());
    }

    private static EntryContext withPendingSignals(EntryContext c, List<ExecutorSignal> pending) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                pending, c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing());
    }

    private static EntryContext withPrice(EntryContext c, BigDecimal price) {
        return new EntryContext(c.account(), price, c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), c.openHeat(), c.openMechanisms(), c.fxToAccount(),
                c.missing());
    }

    private static EntryContext withOpenHeat(EntryContext c, BigDecimal openHeat) {
        return new EntryContext(c.account(), c.price(), c.atr(), c.swingLow(), c.adv20Notional(),
                c.dayHigh(), c.candidateSector(), c.openPositions(), c.activeCooldowns(),
                c.pendingSignals(), c.entriesThisWeek(), c.signalAgeTradingDays(), c.trancheAmount(),
                c.totalBudget(), c.openExposure(), openHeat, c.openMechanisms(), c.fxToAccount(),
                c.missing());
    }

    private static EntryContext unavailableContext() {
        return new EntryContext(null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), 0, -1L, null, null, null, null,
                Map.of(), BigDecimal.ONE, List.of("price", "atr"));
    }

    private ExecutorPosition openPosition(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop) {
        return new ExecutorPosition(id, "depot-1", symbol, side, new BigDecimal("10"),
                entry, initialStop, initialStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null);
    }

    /** Same fixture as {@link #openPosition} but with an explicit {@code qty} and
     *  {@code trimCount} for scale-out/ladder tests. */
    private ExecutorPosition openPosition(long id, String symbol, String side,
            BigDecimal entry, BigDecimal initialStop, BigDecimal qty, int trimCount) {
        return new ExecutorPosition(id, "depot-1", symbol, side, qty,
                entry, initialStop, initialStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null, null,
                null, null, null, null, trimCount, null, null);
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
                assembler, customSizer, ranker, tranche2Detector, telegram, positionContextRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, fixedClock);
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
        assertThat(vetoResults.size()).isEqualTo(14);
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
        assertThat(log.vetoResults().size()).isEqualTo(14);
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
        assertThat(log.vetoResults().size()).isEqualTo(14);
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
        // take-profit is now guaranteed: reference=100, stop=95 → R=5 → 100 + 3*5 = 115
        assertThat(req.takeProfitLimit()).isEqualByComparingTo("115");
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
        assertThat(vetoResults.size()).isEqualTo(14);
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
        assertThat(order.path("take_profit").asDouble()).isEqualTo(115.0);
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
        assertThat(log.vetoResults().size()).isEqualTo(14);
    }

    @Test
    void placeEntry_brokerError_underCap_leavesPending() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenThrow(new BrokerUnavailableException("agora order rejected: market closed"));
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(1);

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
        when(decisionRepo.countByReason("sig-1", "BROKER_ERROR")).thenReturn(3);

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
    // guard, take-profit synthesis, and booking -- never the stale signal reference
    // -------------------------------------------------------------------

    @Test
    void placeEntry_divergentPrices_usesFreshPriceBasis() {
        // Stale signal.referencePrice=110 vs fresh ctx.price()=100 (happyContext: atr=2, no
        // swingLow -> SELL stop window [105, 106.5], since price fell after the signal's reference
        // was captured). stop=106 sits inside that fresh window and is > orderPrice(100), so the
        // fresh-basis guard passes. The OLD stale-reference guard would have wrongly rejected this
        // same order: 106 is not > referencePrice(110), so its direction check would fail with
        // NO_STOP. CHASED_AWAY only fires when price rises away from the reference, so a falling
        // price never trips it here (price(100) <= referencePrice(110) + atr(2) trivially holds).
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("110")));
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
        // basis for sizing and booking (BracketRequest.limitPrice itself is untouched -- it always
        // carries the LLM's raw argument, null or not).
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
    // place-entry: guaranteed take-profit leg (Agora requires one)
    // -------------------------------------------------------------------

    @Test
    void placeEntry_noTakeProfit_synthesizesWide3RTarget_buy() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // BUY, reference=100, stop=95 → R=5 → target = 100 + 3*5 = 115, no take_profit supplied
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNotNull();
        assertThat(reqCaptor.getValue().takeProfitLimit()).isEqualByComparingTo("115");
    }

    @Test
    void placeEntry_noTakeProfit_synthesizesWide3RTarget_sell() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // SELL, reference=100, stop=105 → R=5 → target = 100 - 3*5 = 85 (below entry)
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"SELL","stop_price":105}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("depot-1"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNotNull();
        assertThat(reqCaptor.getValue().takeProfitLimit()).isEqualByComparingTo("85");
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
                position.trimCount(), position.lowestPrice(), position.entryExpiresAt());
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
                "2026-07-03T00:00:42Z");
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
        verify(positionRepo).close(eq(7L), exitPriceCaptor.capture(), realizedRCaptor.capture(), eq("SOFT_CHANDELIER"));
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("112");
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("2.4");

        verify(cooldownRepo).add(eq("ACME"), eq("SOFT_CHANDELIER"), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("SOFT_TRIGGER");
        assertThat(log.action()).isEqualTo("EXIT_FULL");
        assertThat(log.confidenceInDecision()).isEqualTo(0.7);
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
        verify(positionRepo).close(eq(7L), any(), any(), eq("SOFT_CHANDELIER"));

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
        verify(positionRepo).close(eq(7L), any(), any(), eq("SOFT_CHANDELIER"));
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
        verify(positionRepo).close(eq(7L), any(), any(), eq("SCALE_OUT"));
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
                Map.of(), BigDecimal.ONE, List.of());
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
                null, null, null, null, null, null, null, null, null, 0, null, null);
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
                null, null, null, null, null, null, null, null, null, 0, null, null);
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
