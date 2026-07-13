package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroparWebhookControllerTest {

    private static final String BEARER = "Bearer tok";
    private static final String CONNECTION = "depot-1";

    private HeldPositionService heldPositionService;
    private AgoraMarketData marketData;
    private ExitSignalRepository exitSignalRepo;
    private TelegramNotifier telegram;
    private ObjectMapper mapper;

    private GroparWebhookController controller;

    @BeforeEach
    void setUp() {
        heldPositionService = mock(HeldPositionService.class);
        marketData          = mock(AgoraMarketData.class);
        exitSignalRepo      = mock(ExitSignalRepository.class);
        telegram            = mock(TelegramNotifier.class);
        mapper              = JsonMapper.builder().build();

        AgoraResearch research = mock(AgoraResearch.class);
        when(research.exitTa(any(), anyInt(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ExitTa(new BigDecimal("2"), true, new BigDecimal("25"), false,
                        new BigDecimal("105"), true, new BigDecimal("100"), true, "BULLISH",
                        new BigDecimal("120"), new BigDecimal("90"), true));
        var indicatorService = new GroparExitIndicators(research, 22, new BigDecimal("3.0"), 50, 200, 250);

        var riskService = new RiskMetricsService(new RiskMetricsService.Params(
                new java.math.BigDecimal("3.0"), new java.math.BigDecimal("1.5"),
                new java.math.BigDecimal("0.35"), new java.math.BigDecimal("2.0")));

        var cache = new ToolFetchCache(new AgentToolCatalog(java.util.List.of()), 0);

        when(exitSignalRepo.insert(any(ExitSignal.class), any())).thenReturn(true);

        controller = new GroparWebhookController(
                "tok",
                heldPositionService, marketData, exitSignalRepo, telegram,
                indicatorService, riskService, cache, mapper,
                CONNECTION,
                "alice@x",   // dracul.primary-user-email
                260,  // historyDays
                40.0, // profitTargetPct
                15.0, // stopLossPct
                0L    // fetchThrottleMs (no sleep in tests)
        );
    }

    // -------------------------------------------------------------------------
    // Helper: minimal OhlcBar list (1 bar — enough for a non-empty result)
    // -------------------------------------------------------------------------

    private List<OhlcBar> oneBar() {
        var c = new BigDecimal("100");
        return List.of(new OhlcBar(LocalDate.of(2025, 6, 1), c, c, c, c, 1_000));
    }

    /** 23 bars (22 TR values) — enough for ATR to be available with atrPeriod=22. */
    private List<OhlcBar> multiBars() {
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < 23; i++) {
            var c = new BigDecimal("100");
            var h = new BigDecimal("105");
            var l = new BigDecimal("95");
            bars.add(new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i), h, h, l, c, 1_000));
        }
        return bars;
    }

    // -------------------------------------------------------------------------
    // Helper: build a HeldPosition (TA-only: no context)
    // -------------------------------------------------------------------------

    private HeldPosition taOnly(String symbol, String entryPrice, String qty) {
        return new HeldPosition(symbol, new BigDecimal(qty), new BigDecimal(entryPrice),
                new BigDecimal("1000"), new BigDecimal("0"),
                null, null, null, null, null, null, null, null);
    }

    private HeldPosition withContext(String symbol, String entryPrice, String qty,
                                      String verdictId, JsonNode killCriteria, String horizon,
                                      JsonNode thesisSnapshot, BigDecimal initialStop) {
        return withContext(symbol, entryPrice, qty, verdictId, killCriteria, horizon,
                thesisSnapshot, initialStop, null);
    }

    private HeldPosition withContext(String symbol, String entryPrice, String qty,
                                      String verdictId, JsonNode killCriteria, String horizon,
                                      JsonNode thesisSnapshot, BigDecimal initialStop, String openedAt) {
        return new HeldPosition(symbol, new BigDecimal(qty), new BigDecimal(entryPrice),
                new BigDecimal("1000"), new BigDecimal("0"),
                verdictId, killCriteria, horizon, thesisSnapshot, initialStop, null, "reconcile",
                openedAt);
    }

    private JsonNode thesisSnapshot(String summary, String horizon) {
        Map<String, Object> thesis = Map.of(
                "summary", summary,
                "signals", List.of("signal1"),
                "risks", List.of("risk1"),
                "anomalyTypes", List.of("SPINOFF"),
                "horizon", horizon);
        return mapper.valueToTree(thesis);
    }

    // =========================================================================
    // Test 1: fetchHeldPositions sources positions from HeldPositionService and
    // enriches them into the same HeldPositionView shape.
    // =========================================================================

    @Test
    void fetchHeldPositions_sourcesFromHeldPositionServiceAndEnriches() throws Exception {
        var pos = taOnly("ACME", "100", "10");
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of(pos));
        when(marketData.dailyOhlcHistory(eq("ACME"), anyInt())).thenReturn(oneBar());

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        var positions = (List<?>) output.get("positions");

        assertThat(positions).hasSize(1);
        var view = (HeldPositionView) positions.get(0);
        assertThat(view.positionId()).isEqualTo("ACME");
        assertThat(view.symbol()).isEqualTo("ACME");
        assertThat(view.entryPrice()).isEqualTo(100.0);
        assertThat(view.shareCount()).isEqualTo(10.0);
        assertThat(view.indicators()).isNotNull();
        verify(marketData, times(1)).dailyOhlcHistory(eq("ACME"), anyInt());
    }

    // =========================================================================
    // Test 2: a null-context position (executor-opened, TA-only) is enriched
    // with indicators but no thesis — never dropped.
    // =========================================================================

    @Test
    void fetchHeldPositions_nullContext_degradesToTaOnlyWithoutDropping() throws Exception {
        var pos = taOnly("EXEC", "50", "5"); // verdictId + all context fields null
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of(pos));
        when(marketData.dailyOhlcHistory(eq("EXEC"), anyInt())).thenReturn(oneBar());

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        var positions = (List<?>) output.get("positions");

        assertThat(positions).hasSize(1); // never dropped
        var view = (HeldPositionView) positions.get(0);
        assertThat(view.indicators()).isNotNull();     // TA present
        assertThat(view.risk()).isNotNull();
        assertThat(view.thesis()).isNull();             // thesis/kill absent
    }

    // =========================================================================
    // Test 3: a position with context includes the thesis block (summary/
    // signals/risks/anomalyTypes/horizon) plus killCriteria when present.
    // =========================================================================

    @Test
    void fetchHeldPositions_withContext_includesThesisAndKillCriteria() throws Exception {
        JsonNode kill = mapper.valueToTree(List.of("Close below 90", "Merger terminated"));
        JsonNode snapshot = thesisSnapshot("summary text", "3-6m");
        var pos = withContext("ACME", "100", "10", "v1", kill, "3-6m", snapshot, new BigDecimal("70"));
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of(pos));
        when(marketData.dailyOhlcHistory(eq("ACME"), anyInt())).thenReturn(oneBar());

        var resp = controller.fetchHeldPositions(BEARER, null);

        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        var positions = (List<?>) output.get("positions");

        assertThat(positions).hasSize(1);
        var view = (HeldPositionView) positions.get(0);
        assertThat(view.thesis()).isNotNull();
        assertThat(view.thesis().get("summary")).isEqualTo("summary text");
        @SuppressWarnings("unchecked")
        var killCriteria = (List<String>) view.thesis().get("killCriteria");
        assertThat(killCriteria).containsExactly("Close below 90", "Merger terminated");
    }

    // =========================================================================
    // Test 3b: buildThesis surfaces kill-criteria even without a (parseable)
    // thesis — the executor-opened-position gap this task closes.
    // =========================================================================

    @Test
    void buildThesis_nullSnapshotButKillCriteria_returnsKillOnlyBlock() {
        JsonNode kill = mapper.valueToTree(List.of("drift reverses"));
        HeldPosition hp = withContext("HELE", "100", "10", "v1", kill, "3-6m",
                null, new BigDecimal("70"));

        Map<String, Object> thesis = controller.buildThesis(hp);

        assertThat(thesis).isNotNull();
        @SuppressWarnings("unchecked")
        var killCriteria = (List<String>) thesis.get("killCriteria");
        assertThat(killCriteria).containsExactly("drift reverses");
    }

    @Test
    void buildThesis_malformedSnapshotButKillCriteria_stillReturnsKillOnly() {
        JsonNode malformed = mapper.valueToTree("not-an-object");
        JsonNode kill = mapper.valueToTree(List.of("k"));
        HeldPosition hp = withContext("HELE", "100", "10", "v1", kill, "3-6m",
                malformed, new BigDecimal("70"));

        Map<String, Object> thesis = controller.buildThesis(hp);

        assertThat(thesis).isNotNull();
        assertThat(thesis).containsKey("killCriteria"); // m1: parse-failure must not swallow kill
    }

    @Test
    void buildThesis_noThesisNoKill_returnsNull() {
        HeldPosition hp = taOnly("HELE", "100", "10");

        assertThat(controller.buildThesis(hp)).isNull();
    }

    // =========================================================================
    // Test 4: fetchHeldPositions swallows per-position market-data failures —
    // the feed still returns 200 and other positions remain enriched.
    // =========================================================================

    @Test
    void fetchHeldPositions_marketDataFailure_skipsThatPositionOnly() throws Exception {
        var good = taOnly("GOOD", "100", "10");
        var bad  = taOnly("BAD", "50", "5");
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of(good, bad));
        when(marketData.dailyOhlcHistory(eq("GOOD"), anyInt())).thenReturn(oneBar());
        when(marketData.dailyOhlcHistory(eq("BAD"), anyInt()))
                .thenThrow(new de.visterion.dracul.marketdata.MarketDataException(
                        de.visterion.dracul.marketdata.MarketDataException.Kind.UNAVAILABLE, "down"));

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var positions = (List<?>) ((Map<String, Object>)
                ((Map<?, ?>) resp.getBody()).get("output")).get("positions");
        assertThat(positions).hasSize(1);
        assertThat(((HeldPositionView) positions.get(0)).symbol()).isEqualTo("GOOD");
    }

    // =========================================================================
    // Test 5: risk metrics are computed from the position's context stop
    // (hp.initialStop()), not a watchlist risk snapshot (that path is gone).
    // =========================================================================

    @Test
    void fetchHeldPositions_usesContextInitialStopForRiskMetrics() throws Exception {
        var pos = withContext("SNAP", "100", "10", null, null, null, null, new BigDecimal("70"));
        when(heldPositionService.openPositions(CONNECTION)).thenReturn(List.of(pos));
        when(marketData.dailyOhlcHistory(eq("SNAP"), anyInt())).thenReturn(multiBars());

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var positions = (List<?>) ((Map<String, Object>)
                ((Map<?, ?>) resp.getBody()).get("output")).get("positions");
        var view = (HeldPositionView) positions.get(0);
        assertThat(view.risk().initialStop()).isEqualByComparingTo("70");
        assertThat(view.risk().r()).isEqualByComparingTo("30"); // entry(100) - stop(70)
    }

    // =========================================================================
    // Test 6: complete persists signals for a held (depot-sourced) position
    // and fires Telegram for non-HOLD actions.
    // =========================================================================

    @Test
    void complete_persistsSignalAndNotifiesTelegramForNonHold() throws Exception {
        when(heldPositionService.openPositions(CONNECTION))
                .thenReturn(List.of(taOnly("ACME", "100", "10")));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "ACME", "symbol": "ACME", "action": "SELL",
                        "rationale": "exit", "confidence": 0.8, "fired_rules": ["DEATH_CROSS"] }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-42", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo).insert(any(ExitSignal.class), eq("alice@x"));
        verify(telegram).notifyAlert(eq("ACME"), eq("EXIT"), eq("SELL"), contains("alice@x"));
    }

    // =========================================================================
    // Test 7: complete with HOLD → persists but does NOT fire Telegram
    // =========================================================================

    @Test
    void complete_holdSignal_persistsButNoTelegram() throws Exception {
        when(heldPositionService.openPositions(CONNECTION))
                .thenReturn(List.of(taOnly("ACME", "100", "10")));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "ACME", "symbol": "ACME", "action": "HOLD",
                        "rationale": "all good", "thesis_status": "INTACT" }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-43", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo).insert(any(ExitSignal.class), eq("alice@x"));
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
    }

    // =========================================================================
    // Test 8: complete with unknown/non-held position_id (hallucinated by the
    // LLM, or the position has since been closed) → skip persist
    // =========================================================================

    @Test
    void complete_unknownPositionId_skipsPersist() throws Exception {
        when(heldPositionService.openPositions(CONNECTION))
                .thenReturn(List.of(taOnly("ACME", "100", "10")));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "GHOST", "symbol": "GHOST", "action": "SELL",
                        "rationale": "hallucinated", "confidence": 0.9 }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-45", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo, never()).insert(any(), any());
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
    }

    // =========================================================================
    // Test 9: complete with null rationale → no "null" literal in Telegram text
    // =========================================================================

    @Test
    void complete_sellSignalWithNullRationale_noNullLiteralInTelegram() throws Exception {
        when(heldPositionService.openPositions(CONNECTION))
                .thenReturn(List.of(taOnly("ACME", "100", "10")));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "ACME", "symbol": "ACME", "action": "SELL", "confidence": 0.7 }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        controller.complete(BEARER, "run-46", body);

        verify(telegram).notifyAlert(eq("ACME"), eq("EXIT"), eq("SELL"),
                argThat(text -> text != null && !text.contains("null")));
    }

    // =========================================================================
    // Test 10: violated_kill_criteria appends "[Verletzt: ...]" to rationale
    // =========================================================================

    @Test
    void complete_invalidatedSignal_appendsViolatedKillCriteriaToRationale() throws Exception {
        when(heldPositionService.openPositions(CONNECTION))
                .thenReturn(List.of(taOnly("ACME", "100", "10")));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "ACME", "symbol": "ACME", "action": "SELL",
                        "rationale": "These loosen", "confidence": 0.9,
                        "thesis_status": "INVALIDATED",
                        "violated_kill_criteria": ["Close below 90"] }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-47", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        ArgumentCaptor<ExitSignal> captor = ArgumentCaptor.forClass(ExitSignal.class);
        verify(exitSignalRepo).insert(captor.capture(), eq("alice@x"));
        assertThat(captor.getValue().rationale())
                .isEqualTo("These loosen [Verletzt: Close below 90]");
        assertThat(captor.getValue().watchlistItemId()).isNull();
    }

    // =========================================================================
    // Test 11: complete with non-done status → no persist
    // =========================================================================

    @Test
    void complete_nonDoneStatus_noPersist() throws Exception {
        String json = """
                {
                  "status": "failed",
                  "output": {
                    "signals": []
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-44", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo, never()).insert(any(), any());
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
        verifyNoInteractions(heldPositionService);
    }

    // =========================================================================
    // Test 12: duplicate /complete delivery for the same run/position → Telegram
    // fires only once. The fake below dedups on (vistierieRunId, symbol) -- the exact
    // key the V29 partial unique index (uq_exit_signals_run_symbol) enforces in the
    // real DB (see ExitSignalRepositoryTest for the real-constraint coverage) -- rather
    // than a canned true/false sequence that would pass regardless of what key the
    // repository actually dedups on.
    // =========================================================================

    @Test
    void complete_duplicateDelivery_notifiesTelegramOnlyOnce() throws Exception {
        when(heldPositionService.openPositions(CONNECTION))
                .thenReturn(List.of(taOnly("ACME", "100", "10")));
        var seenRunSymbol = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        when(exitSignalRepo.insert(any(ExitSignal.class), eq("alice@x"))).thenAnswer(inv -> {
            ExitSignal s = inv.getArgument(0);
            return seenRunSymbol.add(s.vistierieRunId() + "|" + s.symbol());
        });

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "ACME", "symbol": "ACME", "action": "SELL",
                        "rationale": "exit", "confidence": 0.7 }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        controller.complete(BEARER, "run-dup", body);
        controller.complete(BEARER, "run-dup", body);

        verify(exitSignalRepo, times(2)).insert(any(ExitSignal.class), eq("alice@x"));
        verify(telegram, times(1)).notifyAlert(eq("ACME"), eq("EXIT"), eq("SELL"), any());
    }
}
