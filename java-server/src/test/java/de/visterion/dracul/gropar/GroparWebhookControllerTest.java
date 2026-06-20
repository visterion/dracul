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
    private static final String USER = "default";

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
                                Double entryPrice, Double shareCount) {
        return new WatchlistItem(id, ticker, ticker + " Corp",
                110.0, 1.0, "calm", "2025-01-01", tag,
                null, List.of(), List.of(),
                entryPrice, shareCount, USER, null, null);
    }

    // =========================================================================
    // Test 1: fetchHeldPositions filters to only held + entry+shares present
    // =========================================================================

    @Test
    void fetchHeldPositions_returnsOnlyFullyHeldItems() throws Exception {
        var heldFull     = item("id-1", "ACME", "HELD",     100.0, 10.0);
        var trackingItem = item("id-2", "FOO",  "TRACKING", 50.0,  5.0);
        var heldNoEntry  = item("id-3", "BAR",  "HELD",     null,  null);

        when(watchlistRepo.findAllByUser(USER))
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
        verify(marketData, times(1)).dailyOhlcHistory(eq("ACME"), anyInt());
        verify(marketData, never()).dailyOhlcHistory(eq("FOO"),  anyInt());
        verify(marketData, never()).dailyOhlcHistory(eq("BAR"),  anyInt());
    }

    // =========================================================================
    // Test 2: complete with SELL → persists ExitSignal + fires Telegram
    // =========================================================================

    @Test
    void complete_sellSignal_persistsAndFiresTelegram() throws Exception {
        var heldItem = item("id-1", "ACME", "HELD", 100.0, 10.0);
        when(watchlistRepo.findAllByUser(USER)).thenReturn(List.of(heldItem));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      {
                        "symbol": "ACME",
                        "action": "SELL",
                        "rationale": "r",
                        "confidence": 0.8,
                        "fired_rules": ["DEATH_CROSS"]
                      }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-42", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo).insert(any(ExitSignal.class), eq(USER));
        verify(telegram).notifyAlert(eq("ACME"), eq("EXIT"), eq("SELL"), any());
    }

    // =========================================================================
    // Test 3: complete with HOLD → persists but does NOT fire Telegram
    // =========================================================================

    @Test
    void complete_holdSignal_persistsButNoTelegram() throws Exception {
        var heldItem = item("id-1", "ACME", "HELD", 100.0, 10.0);
        when(watchlistRepo.findAllByUser(USER)).thenReturn(List.of(heldItem));

        String json = """
                {
                  "status": "done",
                  "output": {
                    "signals": [
                      {
                        "symbol": "ACME",
                        "action": "HOLD",
                        "rationale": "all good",
                        "thesis_status": "INTACT"
                      }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-43", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo).insert(any(ExitSignal.class), eq(USER));
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
    }

    // =========================================================================
    // Test 4: complete with non-done status → no persist
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
