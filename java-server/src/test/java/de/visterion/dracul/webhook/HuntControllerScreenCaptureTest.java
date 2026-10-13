package de.visterion.dracul.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.benchmark.ScreenCandidateRecorder;
import de.visterion.dracul.benchmark.ScreenItemMapper;
import de.visterion.dracul.benchmark.ScreenItemMappers;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Spec 2026-10-13 §2.3: the capture hook runs inside the fetch supplier, only on a source that is
 *  not unavailable, only for a hunter with a mapper, and can never affect the hunt response. */
class HuntControllerScreenCaptureTest {

    record SynItem(String symbol, String companyName) {}

    private static final List<SynItem> ITEMS =
            List.of(new SynItem("SYNA", "Synthetic A"), new SynItem("", "Synthetic Corp (SYNB)"));

    static final class Probe extends HuntController {
        private final DataSourceResult<?> result;
        private final ScreenItemMapper mapper;

        Probe(DataSourceResult<?> result, ScreenItemMapper mapper, ToolFetchCache cache) {
            super("test-token", Mockito.mock(PreyRepository.class), cache,
                    Mockito.mock(HiveMemResearchService.class), Mockito.mock(ResearchMemoryLinkRepository.class));
            this.result = result;
            this.mapper = mapper;
        }

        @Override protected String agentName() { return "strigoi-merger"; }
        @Override protected DataSourceResult<?> hunt(Map<String, Object> body) { return result; }
        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "fetch_test_candidates"; }
        @Override protected ScreenItemMapper screenItemMapper() { return mapper; }

        ResponseEntity<Map<String, Object>> fetch() {
            return handleFetch("Bearer test-token", Map.of("input", Map.of()));
        }
    }

    private static Probe probe(AnnotationConfigApplicationContext ctx, DataSourceResult<?> result,
                               ScreenItemMapper mapper, ScreenCandidateRecorder recorder) {
        ctx.registerBean(ScreenCandidateRecorder.class, () -> recorder);
        ctx.registerBean(ToolFetchCache.class, () -> new ToolFetchCache(new AgentToolCatalog(List.of()), 300));
        ctx.registerBean(Probe.class, () -> new Probe(result, mapper, ctx.getBean(ToolFetchCache.class)));
        ctx.refresh();
        return ctx.getBean(Probe.class);
    }

    @Test
    void healthyFetchHandsTheItemsToTheRecorder() {
        ScreenCandidateRecorder rec = Mockito.mock(ScreenCandidateRecorder.class);
        try (var ctx = new AnnotationConfigApplicationContext()) {
            probe(ctx, DataSourceResult.healthy("test", ITEMS), ScreenItemMappers.MERGER, rec).fetch();
        }
        verify(rec).record(eq("strigoi-merger"), same(ScreenItemMappers.MERGER), eq(ITEMS));
    }

    @Test
    void degradedButHealthyFetchIsCapturedToo() {
        ScreenCandidateRecorder rec = Mockito.mock(ScreenCandidateRecorder.class);
        var degraded = new DataSourceResult<>(ITEMS, DataSourceHealth.degraded("test", "capped at 2", false, true));
        try (var ctx = new AnnotationConfigApplicationContext()) {
            probe(ctx, degraded, ScreenItemMappers.MERGER, rec).fetch();
        }
        verify(rec).record(eq("strigoi-merger"), same(ScreenItemMappers.MERGER), eq(ITEMS));
    }

    @Test
    void unavailableFetchIsNeverCaptured() {
        ScreenCandidateRecorder rec = Mockito.mock(ScreenCandidateRecorder.class);
        try (var ctx = new AnnotationConfigApplicationContext()) {
            probe(ctx, DataSourceResult.unavailable("test", "down"), ScreenItemMappers.MERGER, rec).fetch();
        }
        verifyNoInteractions(rec);
    }

    @Test
    void aHunterWithoutMapperCapturesNothing() {
        ScreenCandidateRecorder rec = Mockito.mock(ScreenCandidateRecorder.class);
        try (var ctx = new AnnotationConfigApplicationContext()) {
            probe(ctx, DataSourceResult.healthy("test", ITEMS), null, rec).fetch();
        }
        verifyNoInteractions(rec);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aThrowingRecorderNeverChangesTheResponse() {
        ScreenCandidateRecorder rec = Mockito.mock(ScreenCandidateRecorder.class);
        doThrow(new IllegalStateException("synthetic")).when(rec).record(any(), any(), any());
        Logger logger = (Logger) LoggerFactory.getLogger(Probe.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ResponseEntity<Map<String, Object>> resp =
                    probe(ctx, DataSourceResult.healthy("test", ITEMS), ScreenItemMappers.MERGER, rec).fetch();
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> output = (Map<String, Object>) resp.getBody().get("output");
            assertThat(output.get("candidates")).isEqualTo(ITEMS);
            assertThat(((Map<String, Object>) output.get("data_source_health")).get("status")).isEqualTo("healthy");
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).startsWith("screen-candidate capture failed for strigoi-merger");
        });
    }
}
