package de.visterion.dracul.webhook;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 9 write-back-hook coverage for the prey seam (spec §6/§11): {@link HuntController#complete}
 * calls {@link HiveMemResearchService#writeThesisMemory} + inserts a {@code research_memory_link}
 * row once per newly-inserted prey, strictly AFTER {@link HuntController#afterPersist}, and never
 * lets a memory-path failure (a degrade OR an unexpected throw) fail the completion.
 */
class HuntControllerMemoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Its afterPersist override calls a distinguishable marker method on the SAME memory mock
     *  instance the base class holds, so an {@link InOrder} verification on that one mock proves
     *  afterPersist ran strictly before the base class's own writeThesisMemory call — i.e. the
     *  memory write really does sit AFTER afterPersist in {@link HuntController#complete}. */
    static class OrderRecordingHuntController extends HuntController {
        final List<String> callOrder = new ArrayList<>();
        private final HiveMemResearchService recordedMemory;

        OrderRecordingHuntController(String token, PreyRepository preyRepo, ToolFetchCache cache,
                HiveMemResearchService memory, ResearchMemoryLinkRepository memoryLinks) {
            super(token, preyRepo, cache, memory, memoryLinks);
            this.recordedMemory = memory;
        }

        @Override protected String agentName() { return "test-strigoi"; }
        @Override protected DataSourceResult<?> hunt(Map<String, Object> body) {
            return DataSourceResult.healthy("test", List.of("item"));
        }
        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "fetch_test_candidates"; }

        @Override
        protected void afterPersist(List<Prey> inserted, JsonNode body) {
            callOrder.add("afterPersist");
            recordedMemory.searchForInput("afterPersist-marker", 0);
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

    private OrderRecordingHuntController newController(AnnotationConfigApplicationContext ctx,
            PreyRepository preyRepo, HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks) {
        ctx.registerBean(PreyRepository.class, () -> preyRepo);
        ctx.registerBean(ToolFetchCache.class, this::newCache);
        ctx.registerBean(OrderRecordingHuntController.class,
                () -> new OrderRecordingHuntController("test-token", preyRepo,
                        ctx.getBean(ToolFetchCache.class), memory, memoryLinks));
        ctx.refresh();
        return ctx.getBean(OrderRecordingHuntController.class);
    }

    @Test
    void newlyInsertedPrey_writesThesisMemoryAndLinkRow() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            when(preyRepo.insertAll(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
            HiveMemResearchService memory = Mockito.mock(HiveMemResearchService.class);
            when(memory.writeThesisMemory(eq("prey"), eq("AAA"), eq("TEST"), eq("t"),
                    any(), any(), any(), eq("3m"), eq("test-strigoi"), eq(0.5), anyString()))
                    .thenReturn(Optional.of("cell-1"));
            ResearchMemoryLinkRepository memoryLinks = Mockito.mock(ResearchMemoryLinkRepository.class);

            var controller = newController(ctx, preyRepo, memory, memoryLinks);
            var resp = controller.complete("Bearer test-token", "run-1", doneBodyWithOnePrey());

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verify(memory, times(1)).writeThesisMemory(eq("prey"), eq("AAA"), eq("TEST"), eq("t"),
                    any(), any(), any(), eq("3m"), eq("test-strigoi"), eq(0.5), anyString());
            verify(memoryLinks, times(1)).insert(eq("prey"), anyString(), eq("AAA"), eq("cell-1"));
        }
    }

    @Test
    void hiveMemDegrade_preyStillPersisted_204_andNoLinkRow() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            when(preyRepo.insertAll(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
            HiveMemResearchService memory = Mockito.mock(HiveMemResearchService.class);
            when(memory.writeThesisMemory(anyString(), anyString(), any(), any(),
                    any(), any(), any(), any(), anyString(), anyDouble(), anyString()))
                    .thenReturn(Optional.empty());
            ResearchMemoryLinkRepository memoryLinks = Mockito.mock(ResearchMemoryLinkRepository.class);

            var controller = newController(ctx, preyRepo, memory, memoryLinks);
            var resp = controller.complete("Bearer test-token", "run-2", doneBodyWithOnePrey());

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verify(preyRepo, times(1)).insertAll(anyList(), any());
            verifyNoInteractions(memoryLinks);
        }
    }

    @Test
    void memoryThrows_completionStillReturns204() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            when(preyRepo.insertAll(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
            HiveMemResearchService memory = Mockito.mock(HiveMemResearchService.class);
            doThrow(new RuntimeException("bug")).when(memory).writeThesisMemory(anyString(),
                    anyString(), any(), any(), any(), any(), any(), any(), anyString(),
                    anyDouble(), anyString());
            ResearchMemoryLinkRepository memoryLinks = Mockito.mock(ResearchMemoryLinkRepository.class);

            var controller = newController(ctx, preyRepo, memory, memoryLinks);
            var resp = controller.complete("Bearer test-token", "run-3", doneBodyWithOnePrey());

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verifyNoInteractions(memoryLinks);
        }
    }

    @Test
    void noPersistablePrey_memoryNeverCalled() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            HiveMemResearchService memory = Mockito.mock(HiveMemResearchService.class);
            ResearchMemoryLinkRepository memoryLinks = Mockito.mock(ResearchMemoryLinkRepository.class);
            var controller = newController(ctx, preyRepo, memory, memoryLinks);

            JsonNode noPrey = JSON.readTree("""
                    {"status":"done","output":{"prey":[]}}
                    """);
            var resp = controller.complete("Bearer test-token", "run-4", noPrey);

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verifyNoInteractions(memory, memoryLinks);
        }
    }

    @Test
    void allDuplicatePrey_memoryNeverCalled() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            when(preyRepo.insertAll(anyList(), any())).thenReturn(List.of());
            HiveMemResearchService memory = Mockito.mock(HiveMemResearchService.class);
            ResearchMemoryLinkRepository memoryLinks = Mockito.mock(ResearchMemoryLinkRepository.class);
            var controller = newController(ctx, preyRepo, memory, memoryLinks);

            var resp = controller.complete("Bearer test-token", "run-5", doneBodyWithOnePrey());

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verifyNoInteractions(memory, memoryLinks);
        }
    }

    @Test
    void afterPersistFiresBeforeMemoryWrite() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            PreyRepository preyRepo = Mockito.mock(PreyRepository.class);
            when(preyRepo.insertAll(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
            HiveMemResearchService memory = Mockito.mock(HiveMemResearchService.class);
            when(memory.writeThesisMemory(anyString(), anyString(), any(), any(),
                    any(), any(), any(), any(), anyString(), anyDouble(), anyString()))
                    .thenAnswer(inv -> Optional.empty());
            ResearchMemoryLinkRepository memoryLinks = Mockito.mock(ResearchMemoryLinkRepository.class);

            var controller = newController(ctx, preyRepo, memory, memoryLinks);
            controller.complete("Bearer test-token", "run-6", doneBodyWithOnePrey());

            assertThat(controller.callOrder).containsExactly("afterPersist");
            InOrder order = inOrder(memory);
            order.verify(memory).searchForInput(eq("afterPersist-marker"), eq(0));
            order.verify(memory).writeThesisMemory(anyString(), anyString(), any(), any(),
                    any(), any(), any(), any(), anyString(), anyDouble(), anyString());
        }
    }
}
