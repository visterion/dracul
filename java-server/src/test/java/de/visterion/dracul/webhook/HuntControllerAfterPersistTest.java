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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Wiring + behaviour-preservation coverage for the {@link HuntController#afterPersist} hook (E4).
 * <p>
 * The hook exists so {@code strigoi-spin} can promote candidates; the other five hunters must keep
 * their pre-hook behaviour. This test stands in for one of those hunters with a controller whose
 * {@code afterPersist} override does nothing but record the call and delegate to {@code super}
 * (the inherited no-op). It pins the contract the base class promises them:
 * <ul>
 *   <li>the hook fires exactly once, after prey insertion, with the list actually inserted;</li>
 *   <li>a "no new prey" completion (all duplicates) never invokes it — so nothing downstream
 *       (promotion, in the real spin hunter) can re-fire on a retried delivery;</li>
 *   <li>the base no-op ({@code super.afterPersist}) neither throws nor changes the 204 outcome.</li>
 * </ul>
 * Uses a bare {@link AnnotationConfigApplicationContext} so the {@code @Autowired ObjectProvider}
 * fields on {@link HuntController} are populated (empty) exactly as in production, mirroring
 * {@link HuntControllerActivePatternsTest}.
 */
class HuntControllerAfterPersistTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Stand-in for a non-spin hunter: records afterPersist calls but keeps the base no-op. */
    static class RecordingHuntController extends HuntController {
        final List<List<Prey>> afterPersistCalls = new ArrayList<>();

        RecordingHuntController(String token, PreyRepository preyRepo, ToolFetchCache cache) {
            super(token, preyRepo, cache, Mockito.mock(HiveMemResearchService.class),
                    Mockito.mock(ResearchMemoryLinkRepository.class));
        }

        @Override protected String agentName() { return "test-strigoi"; }
        @Override protected DataSourceResult<?> hunt(Map<String, Object> body) {
            return DataSourceResult.healthy("test", List.of("item"));
        }
        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "fetch_test_candidates"; }

        @Override
        protected void afterPersist(List<Prey> inserted, JsonNode body) {
            afterPersistCalls.add(List.copyOf(inserted));
            super.afterPersist(inserted, body); // the inherited no-op the real hunters use
        }
    }

    private ToolFetchCache newCache() {
        return new ToolFetchCache(new AgentToolCatalog(List.of()), 300);
    }

    private JsonNode doneBodyWithOnePrey() {
        return JSON.readTree("""
                {"status":"done","output":{"prey":[
                  {"symbol":"AAA","companyName":"Alpha","anomalyType":"TEST","confidence":0.5,
                   "thesis":"t","signals":[],"risks":[],"kill_criteria":["x"],"horizon":"3m"}
                ]}}
                """);
    }

    private RecordingHuntController newController(AnnotationConfigApplicationContext ctx,
                                                 PreyRepository preyRepo) {
        ctx.registerBean(PreyRepository.class, () -> preyRepo);
        ctx.registerBean(ToolFetchCache.class, this::newCache);
        ctx.registerBean(RecordingHuntController.class,
                () -> new RecordingHuntController("test-token", preyRepo, ctx.getBean(ToolFetchCache.class)));
        ctx.refresh();
        return ctx.getBean(RecordingHuntController.class);
    }

    @Test
    void afterPersistFiresOnceWithInsertedPreyAndReturns204() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            // insertAll echoes its argument: every mapped prey is "newly inserted".
            when(preyRepo.insertAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
            RecordingHuntController controller = newController(ctx, preyRepo);

            var resp = controller.complete("Bearer test-token", "run-1", doneBodyWithOnePrey());

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            assertThat(controller.afterPersistCalls).hasSize(1);
            assertThat(controller.afterPersistCalls.get(0)).hasSize(1);
            assertThat(controller.afterPersistCalls.get(0).get(0).symbol()).isEqualTo("AAA");
        }
    }

    @Test
    void afterPersistNotInvokedWhenNothingNewlyInserted() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            // Duplicate delivery: everything collided on the natural key, nothing inserted.
            when(preyRepo.insertAll(anyList())).thenReturn(List.of());
            RecordingHuntController controller = newController(ctx, preyRepo);

            var resp = controller.complete("Bearer test-token", "run-2", doneBodyWithOnePrey());

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            assertThat(controller.afterPersistCalls).as("hook must not fire on all-duplicates").isEmpty();
        }
    }
}
