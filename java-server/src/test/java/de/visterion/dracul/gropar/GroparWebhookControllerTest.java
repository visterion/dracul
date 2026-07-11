package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.verdict.VerdictDetail;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import de.visterion.dracul.watchlist.PositionRisk;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroparWebhookControllerTest {

    private static final String BEARER = "Bearer tok";

    private WatchlistRepository watchlistRepo;
    private VerdictRepository verdictRepo;
    private PreyRepository preyRepo;
    private AgoraMarketData marketData;
    private ExitSignalRepository exitSignalRepo;
    private TelegramNotifier telegram;

    private GroparWebhookController controller;

    @BeforeEach
    void setUp() {
        watchlistRepo    = mock(WatchlistRepository.class);
        verdictRepo      = mock(VerdictRepository.class);
        preyRepo         = mock(PreyRepository.class);
        marketData       = mock(AgoraMarketData.class);
        exitSignalRepo   = mock(ExitSignalRepository.class);
        telegram         = mock(TelegramNotifier.class);

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

        when(watchlistRepo.positionRiskByItemId()).thenReturn(Map.of());
        when(exitSignalRepo.insert(any(ExitSignal.class), any())).thenReturn(true);

        controller = new GroparWebhookController(
                "tok",
                watchlistRepo, verdictRepo, preyRepo, marketData, exitSignalRepo, telegram,
                indicatorService, riskService, cache,
                260,  // historyDays
                40.0, // profitTargetPct
                15.0, // stopLossPct
                0L    // fetchThrottleMs (no sleep in tests)
        );
    }

    // -------------------------------------------------------------------------
    // Helper: minimal VerdictDetail (only fields the thesis block reads matter)
    // -------------------------------------------------------------------------

    private VerdictDetail detail() {
        return new VerdictDetail(
                "v1", "ACME", "ACME Corp",
                List.of(), 0.8,
                "summary text", "2025-01-01T00:00:00Z",
                List.of("SPINOFF"), 100.0,
                0.8, "3-6m",
                List.of("signal1"), List.of("risk1"),
                List.of(),
                null, null, "USD");
    }

    // -------------------------------------------------------------------------
    // Helper: minimal Prey with given kill criteria
    // -------------------------------------------------------------------------

    private Prey preyWithKillCriteria(String id, List<String> criteria) {
        return new Prey(id, "ACME", "ACME Corp", "SPINOFF",
                0.8, "thesis text", List.of(), List.of(),
                criteria, "3-6m", "strigoi-spin", "2025-01-01T00:00:00Z");
    }

    // -------------------------------------------------------------------------
    // Helper: minimal OhlcBar list (1 bar — enough for a non-empty result)
    // -------------------------------------------------------------------------

    private List<OhlcBar> oneBar() {
        var c = new BigDecimal("100");
        return List.of(new OhlcBar(LocalDate.of(2025, 6, 1), c, c, c, c, 1_000));
    }

    // -------------------------------------------------------------------------
    // Helper: build a WatchlistItem
    // -------------------------------------------------------------------------

    private WatchlistItem item(String id, String ticker, String tag,
                                Double entryPrice, Double shareCount, String owner) {
        return new WatchlistItem(id, ticker, ticker + " Corp",
                110.0, 1.0, "calm", "2025-01-01", tag,
                null, List.of(), List.of(),
                entryPrice, shareCount, owner, null, null);
    }

    private WatchlistItem itemWithVerdict(String id, String ticker, String tag,
                                           Double entryPrice, Double shareCount, String owner,
                                           String verdictId) {
        return new WatchlistItem(id, ticker, ticker + " Corp",
                110.0, 1.0, "calm", "2025-01-01", tag,
                verdictId, List.of(), List.of(),
                entryPrice, shareCount, owner, null, null);
    }

    // =========================================================================
    // Test 1: fetchHeldPositions filters to only held + entry+shares present
    // =========================================================================

    @Test
    void fetchHeldPositions_returnsOnlyFullyHeldItems() throws Exception {
        var heldFull     = item("id-1", "ACME", "HELD",     100.0, 10.0, "alice@x");
        var trackingItem = item("id-2", "FOO",  "TRACKING", 50.0,  5.0,  "alice@x");
        var heldNoEntry  = item("id-3", "BAR",  "HELD",     null,  null, "bob@x");

        when(watchlistRepo.findAll())
                .thenReturn(List.of(heldFull, trackingItem, heldNoEntry));
        when(marketData.dailyOhlcHistory(eq("ACME"), anyInt()))
                .thenReturn(oneBar());

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        var positions = (List<?>) output.get("positions");

        assertThat(positions).hasSize(1);
        assertThat(((HeldPositionView) positions.get(0)).positionId()).isEqualTo("id-1");
        verify(marketData, times(1)).dailyOhlcHistory(eq("ACME"), anyInt());
        verify(marketData, never()).dailyOhlcHistory(eq("FOO"),  anyInt());
        verify(marketData, never()).dailyOhlcHistory(eq("BAR"),  anyInt());
    }

    // =========================================================================
    // Test 1b: fetchHeldPositions thesis block includes deduplicated union of
    // kill criteria across contributing prey
    // =========================================================================

    @Test
    void fetchHeldPositions_includesKillCriteriaFromContributingPrey() throws Exception {
        var heldItem = itemWithVerdict("id-kc", "ACME", "HELD", 100.0, 10.0, "alice@x", "v1");
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));
        when(marketData.dailyOhlcHistory(eq("ACME"), anyInt())).thenReturn(oneBar());

        when(verdictRepo.findDetailById("v1")).thenReturn(Optional.of(detail()));
        when(verdictRepo.contributingPreyIdsById("v1")).thenReturn(List.of("p1", "p2"));
        when(preyRepo.findByIds(List.of("p1", "p2"))).thenReturn(List.of(
                preyWithKillCriteria("p1", List.of("Close below 90")),
                preyWithKillCriteria("p2", List.of("Close below 90", "Merger terminated"))));

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        var positions = (List<?>) output.get("positions");

        assertThat(positions).hasSize(1);
        var view = (HeldPositionView) positions.get(0);
        @SuppressWarnings("unchecked")
        var killCriteria = (List<String>) view.thesis().get("killCriteria");
        assertThat(killCriteria).containsExactly("Close below 90", "Merger terminated");
    }

    // =========================================================================
    // Test 1c: fetchHeldPositions thesis block omits killCriteria key when a
    // repo failure occurs while resolving it — the feed must not break.
    // =========================================================================

    @Test
    void fetchHeldPositions_killCriteriaLookupFailure_omitsKeyButDoesNotBreakFeed() throws Exception {
        var heldItem = itemWithVerdict("id-kc2", "ACME", "HELD", 100.0, 10.0, "alice@x", "v1");
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));
        when(marketData.dailyOhlcHistory(eq("ACME"), anyInt())).thenReturn(oneBar());

        when(verdictRepo.findDetailById("v1")).thenReturn(Optional.of(detail()));
        when(verdictRepo.contributingPreyIdsById("v1")).thenThrow(new RuntimeException("db down"));

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        var positions = (List<?>) output.get("positions");

        assertThat(positions).hasSize(1);
        var view = (HeldPositionView) positions.get(0);
        assertThat(view.thesis()).doesNotContainKey("killCriteria");
    }

    // =========================================================================
    // Test 2: complete routes each signal to its owner, even for the same symbol
    // =========================================================================

    @Test
    void complete_routesEachSignalToItsOwner_evenSameSymbol() throws Exception {
        // Two users both hold AAPL — routing must be by position_id, not symbol.
        var aliceAapl = item("pos-alice", "AAPL", "HELD", 100.0, 10.0, "alice@x");
        var bobAapl   = item("pos-bob",   "AAPL", "HELD", 200.0,  5.0, "bob@x");
        when(watchlistRepo.findAll()).thenReturn(List.of(aliceAapl, bobAapl));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "pos-alice", "symbol": "AAPL", "action": "SELL",
                        "rationale": "alice exit", "confidence": 0.8, "fired_rules": ["DEATH_CROSS"] },
                      { "position_id": "pos-bob", "symbol": "AAPL", "action": "HOLD",
                        "rationale": "bob holds", "confidence": 0.6 }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-42", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo).insert(any(ExitSignal.class), eq("alice@x"));
        verify(exitSignalRepo).insert(any(ExitSignal.class), eq("bob@x"));
        verify(telegram).notifyAlert(eq("AAPL"), eq("EXIT"), eq("SELL"), contains("alice@x"));
        verify(telegram, never()).notifyAlert(any(), any(), eq("HOLD"), any());
    }

    // =========================================================================
    // Test 3: complete with HOLD → persists but does NOT fire Telegram
    // =========================================================================

    @Test
    void complete_holdSignal_persistsButNoTelegram() throws Exception {
        var heldItem = item("id-1", "ACME", "HELD", 100.0, 10.0, "alice@x");
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "id-1", "symbol": "ACME", "action": "HOLD",
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
    // Test 4: complete with unknown position_id → skip persist
    // =========================================================================

    @Test
    void complete_unknownPositionId_skipsPersist() throws Exception {
        var heldItem = item("id-1", "ACME", "HELD", 100.0, 10.0, "alice@x");
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "ghost", "symbol": "ACME", "action": "SELL",
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
    // Test 5: complete with null rationale → no "null" literal in Telegram text
    // =========================================================================

    @Test
    void complete_sellSignalWithNullRationale_noNullLiteralInTelegram() throws Exception {
        var heldItem = item("id-1", "ACME", "HELD", 100.0, 10.0, "alice@x");
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "id-1", "symbol": "ACME", "action": "SELL", "confidence": 0.7 }
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
    // Test 5b: complete with violated_kill_criteria appends "[Verletzt: ...]"
    // to the persisted rationale.
    // =========================================================================

    @Test
    void complete_invalidatedSignal_appendsViolatedKillCriteriaToRationale() throws Exception {
        var heldItem = item("id-1", "ACME", "HELD", 100.0, 10.0, "alice@x");
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "id-1", "symbol": "ACME", "action": "SELL",
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
    }

    // =========================================================================
    // Test 6: complete with non-done status → no persist (was Test 5)
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
    }

    // =========================================================================
    // Test 6b: duplicate /complete delivery for the same run/position → Telegram
    // fires only once, since the second insert reports no fresh row.
    // =========================================================================

    @Test
    void complete_duplicateDelivery_notifiesTelegramOnlyOnce() throws Exception {
        var heldItem = item("id-1", "ACME", "HELD", 100.0, 10.0, "alice@x");
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));
        when(exitSignalRepo.insert(any(ExitSignal.class), eq("alice@x")))
                .thenReturn(true)   // first delivery: fresh row
                .thenReturn(false); // retried delivery: conflict, no new row

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      { "position_id": "id-1", "symbol": "ACME", "action": "SELL",
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

    // =========================================================================
    // Test 7: feed includes risk metrics and lazily freezes initial stop
    // =========================================================================

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

    @Test
    void feedIncludesRiskMetricsAndFreezesStop() throws Exception {
        var heldItem = item("id-r", "RSK", "HELD", 100.0, 10.0, "alice@x");

        // No stored stop yet → riskService will derive one from ATR (derivedNow = true)
        when(watchlistRepo.positionRiskByItemId()).thenReturn(Map.of());
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));
        when(marketData.dailyOhlcHistory(eq("RSK"), anyInt())).thenReturn(multiBars());

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) ((Map<?, ?>) resp.getBody()).get("output");
        @SuppressWarnings("unchecked")
        var positions = (List<?>) output.get("positions");

        assertThat(positions).hasSize(1);
        var view = (HeldPositionView) positions.get(0);
        assertThat(view.risk()).isNotNull();
        assertThat(view.risk().initialStop()).isNotNull();

        verify(watchlistRepo).updateInitialStop(eq("id-r"), any());
    }

    // =========================================================================
    // Test 8: fetchHeldPositions persists risk snapshot (active-stop/+2R/close)
    // =========================================================================

    /** 23 bars with high=105/low=95/close=100 + frozen initial stop at 70
     *  → R = entry(100) - stop(70) = 30 → next_target_2r = 100 + 2*30 = 160.
     *  Chandelier will be computed from ATR; active_stop = max(70, chandelier). */
    @Test
    void fetchPersistsRiskSnapshot() throws Exception {
        var heldItem = item("id-snap", "SNAP", "HELD", 100.0, 10.0, "alice@x");

        // Provide a frozen stop of 70 so R is available
        var pr = new PositionRisk("id-snap", "2025-01-01", new BigDecimal("70"),
                null, null, null, null);
        when(watchlistRepo.positionRiskByItemId()).thenReturn(Map.of("id-snap", pr));
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));
        when(marketData.dailyOhlcHistory(eq("SNAP"), anyInt())).thenReturn(multiBars());

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var positions = (List<?>) ((Map<String, Object>)
                ((Map<?, ?>) resp.getBody()).get("output")).get("positions");
        assertThat(positions).hasSize(1);

        // Capture the updateRiskSnapshot call
        ArgumentCaptor<BigDecimal> stopCaptor   = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> tgtCaptor    = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> closeCaptor  = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> atrCaptor    = ArgumentCaptor.forClass(BigDecimal.class);
        verify(watchlistRepo).updateRiskSnapshot(
                eq("id-snap"), stopCaptor.capture(), tgtCaptor.capture(),
                closeCaptor.capture(), atrCaptor.capture(), any(Instant.class));

        // next_target_2r = 100 + 2*30 = 160
        assertThat(tgtCaptor.getValue()).isEqualByComparingTo("160");
        // active_stop >= initial stop of 70 (may be higher if chandelier > 70)
        assertThat(stopCaptor.getValue().compareTo(new BigDecimal("70"))).isGreaterThanOrEqualTo(0);
        // close must be present (bars all close at 100)
        assertThat(closeCaptor.getValue()).isEqualByComparingTo("100");
        // ATR now sourced from Agora (get_indicators) via the mocked AgoraResearch stub → 2
        assertThat(atrCaptor.getValue()).isEqualByComparingTo("2");
    }

    // =========================================================================
    // Test 9: snapshot write failure is swallowed — fetch still returns 200
    // =========================================================================

    @Test
    void fetchSwallowsSnapshotWriteFailure() throws Exception {
        var heldItem = item("id-fail", "FAIL", "HELD", 100.0, 10.0, "alice@x");

        when(watchlistRepo.positionRiskByItemId()).thenReturn(Map.of());
        when(watchlistRepo.findAll()).thenReturn(List.of(heldItem));
        when(marketData.dailyOhlcHistory(eq("FAIL"), anyInt())).thenReturn(multiBars());

        doThrow(new RuntimeException("db down"))
                .when(watchlistRepo).updateRiskSnapshot(any(), any(), any(), any(), any(), any());

        var resp = controller.fetchHeldPositions(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        var positions = (List<?>) ((Map<String, Object>)
                ((Map<?, ?>) resp.getBody()).get("output")).get("positions");
        assertThat(positions).hasSize(1);
    }
}
