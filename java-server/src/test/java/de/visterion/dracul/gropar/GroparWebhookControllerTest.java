package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private MarketDataPort marketData;
    private ExitSignalRepository exitSignalRepo;
    private TelegramNotifier telegram;

    private GroparWebhookController controller;

    @BeforeEach
    void setUp() {
        watchlistRepo    = mock(WatchlistRepository.class);
        verdictRepo      = mock(VerdictRepository.class);
        marketData       = mock(MarketDataPort.class);
        exitSignalRepo   = mock(ExitSignalRepository.class);
        telegram         = mock(TelegramNotifier.class);

        var indicatorService = new ExitIndicatorService(
                new ExitIndicatorService.Params(22, new BigDecimal("3.0"), 50, 200, 250));

        var cache = new ToolFetchCache(new AgentToolCatalog(java.util.List.of()), 0);

        controller = new GroparWebhookController(
                "tok",
                watchlistRepo, verdictRepo, marketData, exitSignalRepo, telegram,
                indicatorService, cache,
                260,  // historyDays
                40.0, // profitTargetPct
                15.0  // stopLossPct
        );
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
    // Test 5: complete with non-done status → no persist
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
}
