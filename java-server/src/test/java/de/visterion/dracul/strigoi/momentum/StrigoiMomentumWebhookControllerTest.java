package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrigoiMomentumWebhookControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final MomentumRankingService ranking = mock(MomentumRankingService.class);
    private final MomentumCompletionService completion = mock(MomentumCompletionService.class);

    private StrigoiMomentumWebhookController controller() {
        return new StrigoiMomentumWebhookController("tok", mock(PreyRepository.class),
                new ToolFetchCache(new AgentToolCatalog(List.of()), 0),
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class), ranking,
                completion);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(org.springframework.http.ResponseEntity<Map<String, Object>> r) {
        return (Map<String, Object>) r.getBody().get("output");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> health(Map<String, Object> out) {
        return (Map<String, Object>) out.get("data_source_health");
    }

    @Test
    void withoutATokenIs401() {
        assertThat(controller().fetchRanking(null, "run-1", null).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(ranking);
    }

    /** Spec §5.3 (R2 Minor): the snapshot is keyed by the run id — no header, no ranking. */
    @Test
    void withoutARunIdTheToolIsGuardedUnavailable() {
        var out = output(controller().fetchRanking("Bearer tok", null, null));

        assertThat(health(out)).containsEntry("status", "unavailable");
        assertThat((String) health(out).get("detail")).startsWith("tool-guard: missing X-Vistierie-Run-Id");
        verifyNoInteractions(ranking);
    }

    @Test
    void aFailingRankingIsGuardedUnavailable() {
        when(ranking.rank(anyString())).thenThrow(new IllegalStateException("db down"));

        var out = output(controller().fetchRanking("Bearer tok", "run-2", Map.of()));

        assertThat(health(out)).containsEntry("status", "unavailable");
        assertThat((String) health(out).get("detail")).startsWith("tool-guard: ");
    }

    @Test
    void theRankingIsReturnedForTheRun() {
        when(ranking.rank("run-3")).thenReturn(Map.of("ranking", Map.of("rebalance_due", false),
                "data_source_health", Map.of("status", "healthy")));

        var out = output(controller().fetchRanking("Bearer tok", "run-3", Map.of()));

        assertThat(out).containsKey("ranking");
        verify(ranking).rank("run-3");
    }

    /** selectForPersist returns ONLY the code-built prey; the LLM's are passed as a count. */
    @Test
    void selectForPersistReturnsTheCodeBuiltPrey() {
        Prey llmPick = new Prey("p-1", "SYNA", "Synthetic A Corp", "MOMENTUM_12_1", 0.9, "t",
                List.of(), List.of(), List.of("k"), "1m", "strigoi-momentum",
                "2026-10-30T22:45:00Z", null);
        Prey codePick = new Prey("p-2", "SYNB", "Synthetic B Corp", "MOMENTUM_12_1", 0.5, "t",
                List.of(), List.of(), List.of("k"), "1m", "strigoi-momentum",
                "2026-10-30T22:45:00Z", null);
        when(completion.complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("run-4"),
                org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(new MomentumCompletionService.Result(List.of(codePick), Map.of()));

        List<Prey> kept = controller().selectForPersist(List.of(llmPick),
                JSON.readTree("{\"status\":\"done\",\"output\":{\"prey\":[]}}"), "run-4");

        assertThat(kept).containsExactly(codePick);
    }

    /** A throwing completion never 500s (no retry storm); the month stays open. */
    @Test
    void aFailingCompletionPersistsNothing() {
        when(completion.complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenThrow(new IllegalStateException("boom"));

        assertThat(controller().selectForPersist(List.of(),
                JSON.readTree("{\"status\":\"done\",\"output\":{\"prey\":[]}}"), "run-5")).isEmpty();
    }
}
