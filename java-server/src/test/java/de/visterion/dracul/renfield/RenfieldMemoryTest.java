package de.visterion.dracul.renfield;

import de.visterion.dracul.events.SseBroadcaster;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 9 write-back-hook coverage for the trade-proposal seam (spec §6, cell-only append -- no
 * research_memory_link row). Each newly-inserted proposal (per-row {@code proposals.insert(...) >
 * 0}, not the batch total) triggers exactly one writeThesisMemory("trade_proposal", ...) call.
 */
class RenfieldMemoryTest {

    private static final String BEARER = "Bearer tok";
    private static final String OWNER = "primary@x.com";

    private TradeProposalRepository proposals;
    private TelegramNotifier notifier;
    private SseBroadcaster broadcaster;
    private HiveMemResearchService memory;
    private RenfieldWebhookController controller;

    @BeforeEach
    void setUp() {
        proposals = mock(TradeProposalRepository.class);
        notifier = mock(TelegramNotifier.class);
        broadcaster = mock(SseBroadcaster.class);
        memory = mock(HiveMemResearchService.class);
        controller = new RenfieldWebhookController("tok", OWNER, proposals, notifier, broadcaster, memory);
    }

    private static JsonNode json(String s) throws Exception {
        return JsonMapper.builder().build().readTree(s);
    }

    @Test
    void perRowFreshInsert_writesOneMemoryCellPerPersistedProposal() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString())).thenReturn(1);

        var resp = controller.complete(BEARER, "run-1", json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"buy","entry_zone":"41.50-42.20","stop":"39.80",
                    "confidence":0.7,"rationale":"guidance cut priced in"},
                   {"symbol":"BETA","action":"trim","entry_zone":"","stop":"",
                    "confidence":0.6,"rationale":"stop proximity alert"}
                ],"market_note":"quiet tape"}}
                """));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(memory, times(1)).writeThesisMemory(eq("trade_proposal"), eq("ACME"), any(), any(),
                any(), any(), any(), any(), any(), anyDouble(), any());
        verify(memory, times(1)).writeThesisMemory(eq("trade_proposal"), eq("BETA"), any(), any(),
                any(), any(), any(), any(), any(), anyDouble(), any());
    }

    @Test
    void perRowDuplicateInsert_doesNotWriteMemoryForThatRow() throws Exception {
        when(proposals.insert(anyString(), eq("ACME"), anyString(), any(), any(), any(),
                anyString(), any(), anyString())).thenReturn(0);
        when(proposals.insert(anyString(), eq("BETA"), anyString(), any(), any(), any(),
                anyString(), any(), anyString())).thenReturn(1);

        controller.complete(BEARER, "run-2", json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                    "confidence":0.7,"rationale":"r"},
                   {"symbol":"BETA","action":"trim","entry_zone":"","stop":"",
                    "confidence":0.6,"rationale":"r2"}
                ],"market_note":"m"}}
                """));

        verify(memory, never()).writeThesisMemory(eq("trade_proposal"), eq("ACME"), any(), any(),
                any(), any(), any(), any(), any(), anyDouble(), any());
        verify(memory, times(1)).writeThesisMemory(eq("trade_proposal"), eq("BETA"), any(), any(),
                any(), any(), any(), any(), any(), anyDouble(), any());
    }

    @Test
    void memoryThrows_completionStillReturns204() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString())).thenReturn(1);
        doThrow(new RuntimeException("bug")).when(memory).writeThesisMemory(anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), anyDouble(), any());

        var resp = controller.complete(BEARER, "run-3", json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                    "confidence":0.7,"rationale":"r"}],"market_note":"m"}}
                """));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(notifier, times(1)).notifyDigest(anyString());
    }
}
