package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.executor.broker.PlacedBracket;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    private JsonMapper mapper;

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
                assembler, sizer, ranker, tranche2Detector, telegram,
                "tkn", "saxo-sim", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2);
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
        return new ExecutorPosition(id, "saxo-sim", symbol, side, new BigDecimal("10"),
                entry, initialStop, initialStop, 1, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", entry, null, 0, null, null, null, null, null,
                null, null, null, null);
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
    void placeEntry_orderGuardWrongStop_noBrokerCall() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":105}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

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
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
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
        verify(gateway, times(1)).placeBracket(eq("saxo-sim"), reqCaptor.capture());
        BracketRequest req = reqCaptor.getValue();
        assertThat(req.symbol()).isEqualTo("ACME");
        assertThat(req.side()).isEqualTo("BUY");
        // qty is server-side sizer output (tranche 1000 / price 100), not caller-supplied.
        assertThat(req.qty()).isEqualByComparingTo("10");
        assertThat(req.stopLossStop()).isEqualByComparingTo("95");
        assertThat(req.limitPrice()).isNull();
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
    }

    @Test
    void placeEntry_brokerError_noPositionBooked() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
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
    }

    @Test
    void placeEntry_dbFailureAfterPlacedBracket_escalatesOrphanedOrder() {
        when(signalRepo.findById("sig-1")).thenReturn(signal("sig-1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
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
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
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
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"SELL","stop_price":106}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("saxo-sim"), reqCaptor.capture());
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
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "sig-1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(77L);

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","limit_price":99,"stop_price":93}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, null, body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("saxo-sim"), reqCaptor.capture());
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
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // BUY, reference=100, stop=95 → R=5 → target = 100 + 3*5 = 115, no take_profit supplied
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("saxo-sim"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNotNull();
        assertThat(reqCaptor.getValue().takeProfitLimit()).isEqualByComparingTo("115");
    }

    @Test
    void placeEntry_noTakeProfit_synthesizesWide3RTarget_sell() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // SELL, reference=100, stop=105 → R=5 → target = 100 - 3*5 = 85 (below entry)
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"SELL","stop_price":105}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("saxo-sim"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isNotNull();
        assertThat(reqCaptor.getValue().takeProfitLimit()).isEqualByComparingTo("85");
    }

    @Test
    void placeEntry_explicitTakeProfit_usedUnchanged() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
                .thenReturn(new PlacedBracket("brk-1", "stop-1", "tp-1", "s1", OrderStatus.WORKING));
        when(positionRepo.insert(any())).thenReturn(1L);

        // LLM supplies take_profit=108 → must be used as-is, not overwritten by the 3R default (115)
        JsonNode body = json("""
                {"run_id":"r1","tool_name":"place_entry",
                 "input":{"signal_id":"s1","symbol":"ACME","side":"BUY","stop_price":95,"take_profit":108}}
                """);

        controller.placeEntry(BEARER, "r1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway, times(1)).placeBracket(eq("saxo-sim"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().takeProfitLimit()).isEqualByComparingTo("108");
    }

    // -------------------------------------------------------------------
    // place-entry: Vistierie envelope ({"run_id","tool_name","input":{...}})
    // -------------------------------------------------------------------

    @Test
    void placeEntry_vistierieEnvelope_placesIdenticallyToTopLevel() {
        when(signalRepo.findById("s1")).thenReturn(signal("s1", 0.9, new BigDecimal("100")));
        when(gateway.placeBracket(eq("saxo-sim"), any(BracketRequest.class)))
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
        verify(gateway, times(1)).placeBracket(eq("saxo-sim"), reqCaptor.capture());
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
    }

    // -------------------------------------------------------------------
    // get-account
    // -------------------------------------------------------------------

    @Test
    void getAccount_unavailableEnvelope() {
        when(gateway.account("saxo-sim")).thenThrow(new BrokerUnavailableException("no session"));

        ResponseEntity<?> resp = controller.getAccount(BEARER, json("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("available")).isEqualTo(false);
        assertThat(output.get("error")).isEqualTo("no session");
    }

    @Test
    void getAccount_happy() {
        when(gateway.account("saxo-sim")).thenReturn(
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
        EnrichedPosition ep = new EnrichedPosition(1L, "saxo-sim", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("104"),
                new BigDecimal("108"), new BigDecimal("2.0"), new BigDecimal("104"),
                new BigDecimal("1.6"), new BigDecimal("1.6"), 5, List.of("X"),
                true, false, 1, true, "R_CONFIRMED", "sig-42");
        when(pipeline.run(eq("saxo-sim"), any())).thenReturn(List.of(ep));

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
        assertThat(first.get("current_price")).isEqualTo(new BigDecimal("108"));
        assertThat(first.get("chandelier_level")).isEqualTo(new BigDecimal("104"));
        assertThat(first.get("kill_criteria")).isEqualTo(List.of("X"));

        @SuppressWarnings("unchecked")
        Map<String, Object> softTrigger = (Map<String, Object>) first.get("soft_trigger");
        assertThat(softTrigger.get("confirm_count")).isEqualTo(1);
        assertThat(softTrigger.get("chandelier_breach")).isEqualTo(true);

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

    // -------------------------------------------------------------------
    // exit-position
    // -------------------------------------------------------------------

    @Test
    void exitPosition_fullExit() {
        ExecutorPosition open = openPosition(7L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(gateway.flatten(eq("saxo-sim"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);
        assertThat(output.get("exit_reason")).isEqualTo("SOFT_CHANDELIER");

        verify(gateway, times(1)).flatten(eq("saxo-sim"), eq("ACME"), eq(BigDecimal.ONE));

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
        when(gateway.flatten(eq("saxo-sim"), eq("ACME"), eq(BigDecimal.ONE)))
                .thenReturn(new CloseResult(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("112"), "close-1"));

        JsonNode body = json("""
                {"run_id":"r1","tool_name":"exit_position",
                 "input":{"symbol":"ACME","reason":"SOFT_CHANDELIER","confidence":0.7}}
                """);

        ResponseEntity<?> resp = controller.exitPosition(BEARER, "r1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("exited")).isEqualTo(true);
        assertThat(output.get("exit_reason")).isEqualTo("SOFT_CHANDELIER");

        verify(gateway, times(1)).flatten(eq("saxo-sim"), eq("ACME"), eq(BigDecimal.ONE));
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
        when(gateway.flatten(eq("saxo-sim"), eq("ACME"), eq(BigDecimal.ONE)))
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
    // add-tranche
    // -------------------------------------------------------------------

    @Test
    void addTranche_eligible_nonDegenerateWeightedAverage() {
        // 10@100 existing + 7@102 add -> weighted-average entry (10*100 + 7*102) / 17 = 100.823529.
        ExecutorPosition open = openPosition(11L, "ACME", "BUY", new BigDecimal("100"), new BigDecimal("95"));
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("saxo-sim"), any()))
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
        when(gateway.placeBracket(eq("saxo-sim"), any()))
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
        verify(gateway).placeBracket(eq("saxo-sim"), reqCaptor.capture());
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
        when(gateway.placeBracket(eq("saxo-sim"), any()))
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
        when(gateway.placeBracket(eq("saxo-sim"), any()))
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
        ExecutorPosition open = new ExecutorPosition(42L, "saxo-sim", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 1, null, List.of("X"), null, "hunter",
                "2026-06-01", null, "OPEN", "brk-1", new BigDecimal("100"), null, 0,
                null, null, null, null, null, null, null, null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(open));
        when(tranche2Detector.detect(eq(open), any(), any(), any()))
                .thenReturn(new Tranche2Detector.Tranche2Status(true, "R_CONFIRMED"));
        when(gateway.placeBracket(eq("saxo-sim"), any()))
                .thenReturn(new PlacedBracket("brk-42", "stop-42", "tp-42", "t2-pos-42", OrderStatus.WORKING));

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        controller.addTranche(BEARER, "run-1", body);

        ArgumentCaptor<BracketRequest> reqCaptor = ArgumentCaptor.forClass(BracketRequest.class);
        verify(gateway).placeBracket(eq("saxo-sim"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().clientRef()).isEqualTo("t2-pos-42");
    }

    @Test
    void addTranche_rejectsWhenTrancheLimitReached() {
        ExecutorPosition open = new ExecutorPosition(7L, "saxo-sim", "ACME", "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 2, null, List.of("X"), "sig-1", "hunter",
                "2026-06-01", null, "OPEN", "brk-1", new BigDecimal("100"), null, 0,
                null, null, null, null, null, null, null, null, null);
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
}
