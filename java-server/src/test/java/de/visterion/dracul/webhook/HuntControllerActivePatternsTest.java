package de.visterion.dracul.webhook;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.prey.PreyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit-level (no Spring Boot context) coverage for the active_patterns wiring in
 *  HuntController#handleFetch. Uses a bare AnnotationConfigApplicationContext so we can
 *  freely control whether a PatternRepository bean exists at all (simulating the
 *  ObjectProvider-empty case), which a full @SpringBootTest context cannot do without
 *  extra bean-exclusion ceremony. */
class HuntControllerActivePatternsTest {

    static class TestHuntController extends HuntController {
        TestHuntController(String token, PreyRepository preyRepo, ToolFetchCache cache) {
            super(token, preyRepo, cache);
        }

        @Override protected String agentName() { return "test-strigoi"; }
        @Override protected DataSourceResult<?> hunt(Map<String, Object> body) {
            return DataSourceResult.healthy("test", List.of("item"));
        }
        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "fetch_test_candidates"; }

        @PostMapping("/tools/fetch-candidates")
        public org.springframework.http.ResponseEntity<Map<String, Object>> fetchCandidates(
                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                @RequestBody Map<String, Object> body) {
            return handleFetch(auth, body);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(AnnotationConfigApplicationContext ctx) {
        TestHuntController controller = ctx.getBean(TestHuntController.class);
        var resp = controller.fetchCandidates("Bearer test-token", Map.of("run_id", "r1", "input", Map.of()));
        return (Map<String, Object>) resp.getBody().get("output");
    }

    private ToolFetchCache newNonCachingCache() {
        return new ToolFetchCache(new AgentToolCatalog(List.of()), 300);
    }

    @Test
    void activePatternsPopulatedFromRepoFilteredByAgentName() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            PatternRepository patternRepo = Mockito.mock(PatternRepository.class);
            when(patternRepo.findAcceptedByStrigoi(eq("test-strigoi")))
                    .thenReturn(List.of("Weight technology spin-offs higher."));

            ctx.registerBean(PreyRepository.class, () -> preyRepo);
            ctx.registerBean(ToolFetchCache.class, this::newNonCachingCache);
            ctx.registerBean(PatternRepository.class, () -> patternRepo);
            ctx.registerBean(TestHuntController.class,
                    () -> new TestHuntController("test-token", preyRepo, ctx.getBean(ToolFetchCache.class)));
            ctx.refresh();

            Map<String, Object> output = fetch(ctx);

            assertThat(output.get("active_patterns")).isEqualTo(List.of("Weight technology spin-offs higher."));
        }
    }

    @Test
    void activePatternsEmptyListWhenRepoHasNone() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            PatternRepository patternRepo = Mockito.mock(PatternRepository.class);
            when(patternRepo.findAcceptedByStrigoi(eq("test-strigoi"))).thenReturn(List.of());

            ctx.registerBean(PreyRepository.class, () -> preyRepo);
            ctx.registerBean(ToolFetchCache.class, this::newNonCachingCache);
            ctx.registerBean(PatternRepository.class, () -> patternRepo);
            ctx.registerBean(TestHuntController.class,
                    () -> new TestHuntController("test-token", preyRepo, ctx.getBean(ToolFetchCache.class)));
            ctx.refresh();

            Map<String, Object> output = fetch(ctx);

            assertThat(output.get("active_patterns")).isEqualTo(List.of());
        }
    }

    @Test
    void activePatternsKeyAbsentWhenPatternRepositoryBeanIsMissing() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);

            ctx.registerBean(PreyRepository.class, () -> preyRepo);
            ctx.registerBean(ToolFetchCache.class, this::newNonCachingCache);
            ctx.registerBean(TestHuntController.class,
                    () -> new TestHuntController("test-token", preyRepo, ctx.getBean(ToolFetchCache.class)));
            ctx.refresh();

            Map<String, Object> output = fetch(ctx);

            assertThat(output).doesNotContainKey("active_patterns");
        }
    }
}
