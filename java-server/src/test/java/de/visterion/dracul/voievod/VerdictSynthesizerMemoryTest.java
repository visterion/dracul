package de.visterion.dracul.voievod;

import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.verdict.VerdictRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 9 write-back-hook coverage for the verdict seam (spec §6/D9): a thesis cell is written
 * ONCE, only on {@link VerdictSynthesizer.Result#INSERTED} (first synthesis for a symbol) — never
 * on UPDATED/SKIPPED_DECIDED/NOOP_UNCHANGED, and never with a {@code research_memory_link} row
 * (verdicts never trade, so there is nothing for Task 10's outcome scan to resolve).
 */
class VerdictSynthesizerMemoryTest {

    private static Prey prey(String symbol, String discoveredBy, double confidence) {
        return new Prey("prey-" + symbol + "-" + discoveredBy, symbol, symbol + " Inc", "TEST",
                confidence, "thesis for " + symbol, List.of("sig"), List.of("risk"), List.of(),
                "3m", discoveredBy, "2026-01-01T00:00:00Z");
    }

    @Test
    void insertedResult_writesThesisMemoryOnceWithNoLinkRow() {
        VerdictRepository verdictRepo = mock(VerdictRepository.class);
        AgoraMarketData marketData = mock(AgoraMarketData.class);
        HiveMemResearchService memory = mock(HiveMemResearchService.class);
        when(memory.writeThesisMemory(anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), anyString(), anyDouble(), anyString()))
                .thenReturn(Optional.of("cell-verdict-1"));
        when(verdictRepo.findActiveBySymbol(eq("ACME"), anyString())).thenReturn(Optional.empty());
        when(marketData.resolve(eq("ACME"))).thenReturn(null);

        var synth = new VerdictSynthesizer(verdictRepo, marketData, memory);
        var cluster = new ConsensusCluster("ACME", "Acme Inc",
                List.of(prey("ACME", "strigoi-echo", 0.7), prey("ACME", "strigoi-spin", 0.6)));

        var result = synth.upsert("ACME", "consensus summary", cluster, "user-1");

        assertThat(result).isEqualTo(VerdictSynthesizer.Result.INSERTED);
        verify(memory, times(1)).writeThesisMemory(eq("verdict"), eq("ACME"), any(), eq("consensus summary"),
                any(), any(), eq(List.of()), anyString(), eq("voievod"), anyDouble(), eq("ACME"));
    }

    @Test
    void updatedResult_doesNotWriteThesisMemory() {
        VerdictRepository verdictRepo = mock(VerdictRepository.class);
        AgoraMarketData marketData = mock(AgoraMarketData.class);
        HiveMemResearchService memory = mock(HiveMemResearchService.class);

        var active = new VerdictRepository.ActiveVerdict("v-1", null, List.of("prey-OLD-strigoi-echo"));
        when(verdictRepo.findActiveBySymbol(eq("ACME"), anyString())).thenReturn(Optional.of(active));
        when(marketData.resolve(eq("ACME"))).thenReturn(null);

        var synth = new VerdictSynthesizer(verdictRepo, marketData, memory);
        var cluster = new ConsensusCluster("ACME", "Acme Inc",
                List.of(prey("ACME", "strigoi-echo", 0.7), prey("ACME", "strigoi-spin", 0.6)));

        var result = synth.upsert("ACME", "consensus summary", cluster, "user-1");

        assertThat(result).isEqualTo(VerdictSynthesizer.Result.UPDATED);
        verify(memory, never()).writeThesisMemory(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyDouble(), any());
    }

    @Test
    void skippedDecidedResult_doesNotWriteThesisMemory() {
        VerdictRepository verdictRepo = mock(VerdictRepository.class);
        AgoraMarketData marketData = mock(AgoraMarketData.class);
        HiveMemResearchService memory = mock(HiveMemResearchService.class);

        var active = new VerdictRepository.ActiveVerdict("v-1", "ACCEPTED", List.of("prey-x"));
        when(verdictRepo.findActiveBySymbol(eq("ACME"), anyString())).thenReturn(Optional.of(active));

        var synth = new VerdictSynthesizer(verdictRepo, marketData, memory);
        var cluster = new ConsensusCluster("ACME", "Acme Inc", List.of(prey("ACME", "strigoi-echo", 0.7)));

        var result = synth.upsert("ACME", "consensus summary", cluster, "user-1");

        assertThat(result).isEqualTo(VerdictSynthesizer.Result.SKIPPED_DECIDED);
        verify(memory, never()).writeThesisMemory(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyDouble(), any());
    }

    @Test
    void noopUnchangedResult_doesNotWriteThesisMemory() {
        VerdictRepository verdictRepo = mock(VerdictRepository.class);
        AgoraMarketData marketData = mock(AgoraMarketData.class);
        HiveMemResearchService memory = mock(HiveMemResearchService.class);

        var cluster = new ConsensusCluster("ACME", "Acme Inc", List.of(prey("ACME", "strigoi-echo", 0.7)));
        var active = new VerdictRepository.ActiveVerdict("v-1", null,
                List.of(cluster.prey().get(0).id()));
        when(verdictRepo.findActiveBySymbol(eq("ACME"), anyString())).thenReturn(Optional.of(active));

        var synth = new VerdictSynthesizer(verdictRepo, marketData, memory);

        var result = synth.upsert("ACME", "consensus summary", cluster, "user-1");

        assertThat(result).isEqualTo(VerdictSynthesizer.Result.NOOP_UNCHANGED);
        verify(memory, never()).writeThesisMemory(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyDouble(), any());
    }

    @Test
    void memoryThrows_insertedResultStillReturned() {
        VerdictRepository verdictRepo = mock(VerdictRepository.class);
        AgoraMarketData marketData = mock(AgoraMarketData.class);
        HiveMemResearchService memory = mock(HiveMemResearchService.class);
        doThrow(new RuntimeException("bug")).when(memory).writeThesisMemory(anyString(), anyString(),
                any(), anyString(), any(), any(), any(), any(), anyString(), anyDouble(), anyString());
        when(verdictRepo.findActiveBySymbol(eq("ACME"), anyString())).thenReturn(Optional.empty());
        when(marketData.resolve(eq("ACME"))).thenReturn(null);

        var synth = new VerdictSynthesizer(verdictRepo, marketData, memory);
        var cluster = new ConsensusCluster("ACME", "Acme Inc",
                List.of(prey("ACME", "strigoi-echo", 0.7), prey("ACME", "strigoi-spin", 0.6)));

        var result = synth.upsert("ACME", "consensus summary", cluster, "user-1");

        assertThat(result).isEqualTo(VerdictSynthesizer.Result.INSERTED);
        verify(verdictRepo, times(1)).insertSynthesized(eq("ACME"), anyString(), any(), anyDouble(),
                anyString(), any(), any(), any(), anyDouble(), anyString(), any(), any(), any(), any(),
                anyString());
    }
}
