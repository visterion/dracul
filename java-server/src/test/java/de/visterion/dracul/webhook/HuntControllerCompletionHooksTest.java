package de.visterion.dracul.webhook;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-03 §4.3 (R2 Major 1): the accepted-completion hook runs BEFORE the empty-prey
 *  early return; selectForPersist decides what is persisted. */
class HuntControllerCompletionHooksTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static class HookedHunter extends HuntController {
        final List<String> accepted = new ArrayList<>();

        HookedHunter(PreyRepository preyRepo, ToolFetchCache cache) {
            super("test-token", preyRepo, cache, Mockito.mock(HiveMemResearchService.class),
                    Mockito.mock(ResearchMemoryLinkRepository.class));
        }

        @Override protected String agentName() { return "test-strigoi"; }
        @Override protected DataSourceResult<?> hunt(Map<String, Object> body) {
            return DataSourceResult.healthy("test", List.of());
        }
        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "fetch_test"; }

        @Override
        protected void onCompletionAccepted(JsonNode body, String runId) {
            accepted.add(runId);
        }

        @Override
        protected List<Prey> selectForPersist(List<Prey> mapped, JsonNode body, String runId) {
            return mapped.stream().filter(p -> !"DROP".equals(p.symbol())).toList();
        }
    }

    private HookedHunter hunter(AnnotationConfigApplicationContext ctx, PreyRepository preyRepo) {
        ctx.registerBean(PreyRepository.class, () -> preyRepo);
        ctx.registerBean(ToolFetchCache.class, () -> new ToolFetchCache(new AgentToolCatalog(List.of()), 300));
        ctx.registerBean(HookedHunter.class, () -> new HookedHunter(preyRepo, ctx.getBean(ToolFetchCache.class)));
        ctx.refresh();
        return ctx.getBean(HookedHunter.class);
    }

    @Test
    void acceptedHookRunsOnAnEmptyPreyListButNotOnAFailedRun() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            HookedHunter h = hunter(ctx, Mockito.mock(PreyRepository.class));

            h.complete("Bearer test-token", "run-ok", JSON.readTree("{\"status\":\"done\",\"output\":{\"prey\":[]}}"));
            h.complete("Bearer test-token", "run-failed", JSON.readTree("{\"status\":\"failed\",\"output\":{}}"));

            assertThat(h.accepted).containsExactly("run-ok");
        }
    }

    /** A run id that arrives padded (e.g. a tool call's header normalized by an intermediary)
     *  must resolve to the same key the run was first seen under: StrigoiMomentumWebhookController
     *  stores the ranking snapshot under runId.trim(), so HuntController#complete must trim the
     *  same way before the hooks and before the insertAll keying — a mismatch here means the
     *  completion can never find the snapshot it just stored. */
    @Test
    void aPaddedRunIdIsTrimmedBeforeTheHooksSeeIt() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            when(preyRepo.insertAll(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
            HookedHunter h = hunter(ctx, preyRepo);

            h.complete("Bearer test-token", "  run-padded  ",
                    JSON.readTree("{\"status\":\"done\",\"output\":{\"prey\":[]}}"));

            assertThat(h.accepted).containsExactly("run-padded");
        }
    }

    @Test
    void selectForPersistDecidesWhatIsInserted() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            when(preyRepo.insertAll(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
            HookedHunter h = hunter(ctx, preyRepo);

            h.complete("Bearer test-token", "run-1", JSON.readTree("""
                    {"status":"done","output":{"prey":[
                      {"symbol":"KEEP","companyName":"K","confidence":0.5,"thesis":"t","kill_criteria":["x"]},
                      {"symbol":"DROP","companyName":"D","confidence":0.5,"thesis":"t","kill_criteria":["x"]}]}}
                    """));

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<Prey>> c = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(preyRepo).insertAll(c.capture(), any());
            assertThat(c.getValue()).extracting(Prey::symbol).containsExactly("KEEP");
        }
    }
}
