package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.position.PositionContextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the tranche-path currency invariant (B5b): {@code add_tranche} can only ever EXTEND a
 * position that {@code place_entry} already created, and {@code place_entry} creates a position
 * only AFTER the full veto catalog (incl. CURRENCY_MISMATCH) passes. Therefore a non-account-
 * currency (EUR/JPY/HKD) find can never reach {@code add_tranche}:
 *
 * <ol>
 *   <li>a EUR find is CURRENCY_MISMATCH-vetoed at {@code place_entry} → NO position is ever booked
 *       (no {@code positionRepo.insert}, no broker order); and</li>
 *   <li>{@code add_tranche} with no matching open position rejects NO_POSITION and places nothing.</li>
 * </ol>
 *
 * The tranche path itself needs no currency check — it structurally cannot act on an un-vetoed
 * instrument. This test locks that structural fact in place.
 */
class TrancheCurrencyInvariantTest {

    private static final String BEARER = "Bearer tkn";
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T00:00:42Z");
    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

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
    private Tranche2Detector tranche2Detector;
    private TelegramNotifier telegram;
    private ExecutorNotifier executorNotifier;
    private PositionContextRepository positionContextRepo;
    private PatternRepository patternRepo;
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
        tranche2Detector = mock(Tranche2Detector.class);
        telegram = mock(TelegramNotifier.class);
        executorNotifier = mock(ExecutorNotifier.class);
        positionContextRepo = mock(PositionContextRepository.class);
        patternRepo = mock(PatternRepository.class);
        when(patternRepo.findEnforced()).thenReturn(List.of());
        mapper = JsonMapper.builder().build();

        when(ruleVersions.active()).thenReturn("exec-v0.2");
        when(positionRepo.findOpen()).thenReturn(List.of());

        controller = new ExecutorWebhookController(
                signalRepo, positionRepo, decisionRepo,
                new VetoService(), new OrderGuard(), gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper,
                assembler, new PositionSizer(), new SignalRanker(), tranche2Detector, telegram,
                executorNotifier, positionContextRepo, patternRepo,
                "tkn", "depot-1", 0.6, 3, 22, 20, 10,
                new BigDecimal("10000"), 10, 0.06, 2, new BigDecimal("5"), 200, 5, 1.0, 2, 2,
                2, 3, 0.0, 3.0, "USD", fixedClock);
    }

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputOf(ResponseEntity<?> resp) {
        return (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
    }

    private ExecutorSignal validSignal() {
        return new ExecutorSignal("sig-1", "hunter", "v1", "ACME", "BUY", 0.9, "PEAD",
                List.of("X"), "3m", new BigDecimal("100"), "PENDING", "2026-07-01T00:00:00Z");
    }

    /** All-vetos-pass context EXCEPT the instrument quote currency, which is the caller's choice. */
    private EntryContext ctxWithCurrency(String quoteCurrency) {
        return new EntryContext(
                new AccountSnapshot(new BigDecimal("10000"), new BigDecimal("10000"), "USD"),
                new BigDecimal("100"), new BigDecimal("2"), null, new BigDecimal("500000"),
                new BigDecimal("101"), "TECH", List.of(), List.of(), List.of(), 0, 0L,
                new BigDecimal("1000"), new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of(), BigDecimal.ONE, List.of(), quoteCurrency);
    }

    @Test
    void placeEntry_eurInstrument_vetoed_noPositionEverBooked() {
        when(signalRepo.findById("sig-1")).thenReturn(validSignal());
        when(assembler.assemble(any())).thenReturn(ctxWithCurrency("EUR"));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("CURRENCY_MISMATCH");
        // The sole position-creation site is guarded behind the veto: no position, no broker order.
        verify(positionRepo, never()).insert(any());
        verify(gateway, never()).placeBracket(any(), any());
    }

    @Test
    void placeEntry_usdInstrument_stillPlaces_usFlowUnchanged() {
        when(signalRepo.findById("sig-1")).thenReturn(validSignal());
        when(assembler.assemble(any())).thenReturn(ctxWithCurrency("USD"));
        when(positionRepo.insert(any())).thenReturn(1L);
        when(gateway.placeBracket(eq("depot-1"), any(BracketRequest.class)))
                .thenReturn(new de.visterion.dracul.executor.broker.PlacedBracket(
                        "brk-1", "stop-1", "tp-1", "sig-1",
                        de.visterion.dracul.executor.broker.OrderStatus.WORKING));

        JsonNode body = json("""
                {"signal_id":"sig-1","symbol":"ACME","side":"BUY","stop_price":95}
                """);

        ResponseEntity<?> resp = controller.placeEntry(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(true);
        verify(positionRepo).insert(any());
        verify(gateway).placeBracket(eq("depot-1"), any(BracketRequest.class));
    }

    @Test
    void addTranche_noOpenPosition_rejectsNoPosition_placesNothing() {
        // No open position exists (a EUR find never created one) → add_tranche has nothing to
        // extend and must reject NO_POSITION without ever reaching the assembler or broker.
        when(positionRepo.findOpen()).thenReturn(List.of());

        JsonNode body = json("""
                {"symbol":"ACME","reason":"tranche-2 add"}
                """);

        ResponseEntity<?> resp = controller.addTranche(BEARER, "run-1", body);

        Map<String, Object> output = outputOf(resp);
        assertThat(output.get("placed")).isEqualTo(false);
        assertThat(output.get("reason")).isEqualTo("NO_POSITION");
        verify(gateway, never()).placeBracket(any(), any());
        verify(assembler, never()).assembleForSymbol(any());
    }
}
