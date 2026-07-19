package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 9 write-back-hook coverage for the exit-signal seam (spec §6, cell-only append -- no
 * research_memory_link row; ExitSignalRepository returns a boolean, not an id). A fresh signal
 * row (whether HOLD or not -- coverage note §8 says outcome-less cells are expected, HOLD is not
 * exempt) triggers exactly one writeThesisMemory("exit_signal", ...) call; a duplicate delivery
 * does not.
 */
class GroparMemoryTest {

    private static final String BEARER = "Bearer tok";
    private static final String CONNECTION = "depot-1";

    private HeldPositionService heldPositionService;
    private AgoraMarketData marketData;
    private ExitSignalRepository exitSignalRepo;
    private TelegramNotifier telegram;
    private HiveMemResearchService memory;
    private ObjectMapper mapper;

    private GroparWebhookController controller;

    @BeforeEach
    void setUp() {
        heldPositionService = mock(HeldPositionService.class);
        marketData          = mock(AgoraMarketData.class);
        exitSignalRepo       = mock(ExitSignalRepository.class);
        telegram             = mock(TelegramNotifier.class);
        memory               = mock(HiveMemResearchService.class);
        mapper               = JsonMapper.builder().build();

        AgoraResearch research = mock(AgoraResearch.class);
        when(research.exitTa(any(), anyInt(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ExitTa(new BigDecimal("2"), true, new BigDecimal("25"), false,
                        new BigDecimal("105"), true, new BigDecimal("100"), true, "BULLISH",
                        new BigDecimal("120"), new BigDecimal("90"), true));
        var indicatorService = new GroparExitIndicators(research, 22, new BigDecimal("3.0"), 50, 200, 250);

        var riskService = new RiskMetricsService(new RiskMetricsService.Params(
                new BigDecimal("3.0"), new BigDecimal("1.5"), new BigDecimal("0.35"), new BigDecimal("2.0")));

        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);

        controller = new GroparWebhookController(
                "tok",
                heldPositionService, marketData, exitSignalRepo, telegram,
                indicatorService, riskService, cache, mapper, memory,
                CONNECTION,
                "alice@x",
                260, 40.0, 15.0, 0L
        );

        when(heldPositionService.openPositions(CONNECTION)).thenReturn(
                List.of(new de.visterion.dracul.position.HeldPosition("ACME", new BigDecimal("10"),
                        new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("0"),
                        null, null, null, null, null, null, null, null, null)));
    }

    private JsonNode signal(String action) {
        return JsonMapper.builder().build().readTree("""
                {"status":"done","output":{"signals":[
                  {"position_id":"ACME","symbol":"ACME","action":"%s","rationale":"r","confidence":0.8}
                ]}}
                """.formatted(action));
    }

    @Test
    void freshNonHoldSignal_writesExitSignalMemoryOnce() {
        when(exitSignalRepo.insert(any(ExitSignal.class), any())).thenReturn(true);

        var resp = controller.complete(BEARER, "run-1", signal("SELL"));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(memory, times(1)).writeThesisMemory(eq("exit_signal"), eq("ACME"), any(), any(),
                any(), any(), any(), any(), any(), anyDouble(), any());
    }

    @Test
    void freshHoldSignal_stillWritesExitSignalMemory() {
        when(exitSignalRepo.insert(any(ExitSignal.class), any())).thenReturn(true);

        var resp = controller.complete(BEARER, "run-2", signal("HOLD"));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(memory, times(1)).writeThesisMemory(eq("exit_signal"), eq("ACME"), any(), any(),
                any(), any(), any(), any(), any(), anyDouble(), any());
    }

    @Test
    void duplicateSignal_doesNotWriteMemory() {
        when(exitSignalRepo.insert(any(ExitSignal.class), any())).thenReturn(false);

        var resp = controller.complete(BEARER, "run-3", signal("SELL"));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(memory);
    }

    @Test
    void memoryThrows_completionStillReturns204() {
        when(exitSignalRepo.insert(any(ExitSignal.class), any())).thenReturn(true);
        doThrow(new RuntimeException("bug")).when(memory).writeThesisMemory(anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), anyDouble(), any());

        var resp = controller.complete(BEARER, "run-4", signal("SELL"));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(exitSignalRepo, times(1)).insert(any(ExitSignal.class), any());
    }
}
