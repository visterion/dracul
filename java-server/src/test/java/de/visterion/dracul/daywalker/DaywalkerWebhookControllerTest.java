package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
import de.visterion.dracul.daywalker.detect.TriggerType;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hivemem.MemoryHit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 11: the {@code /events} handler folds {@code prior_memory} into each emitted payload
 * strictly AFTER {@link DaywalkerEventEngine#detect} returns (W3 -- the deterministic detector
 * must never see memory), and the pre-fetch budget is per-invocation across ALL emitted events
 * (not per-event), mirroring {@code RenfieldScheduler}'s wall-clock-only short-circuit.
 */
class DaywalkerWebhookControllerTest {

    private static final String TOKEN = "dw-tok";
    private static final String BEARER = "Bearer " + TOKEN;

    private static TriggerEvent event(String symbol) {
        return TriggerEvent.watchOnly(symbol, symbol + " Corp", TriggerType.PRICE_SPIKE,
                new BigDecimal("10.00"), Map.of());
    }

    private DaywalkerWebhookController controller(DaywalkerEventEngine engine,
            HiveMemResearchService memory, long priorMemoryBudgetMs) {
        return new DaywalkerWebhookController(TOKEN, engine, mock(DaywalkerCompletionService.class),
                memory, priorMemoryBudgetMs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void priorMemoryIsAssembledStrictlyAfterDetectReturns() {
        var engine = mock(DaywalkerEventEngine.class);
        var memory = mock(HiveMemResearchService.class);
        when(engine.detect(any(), any())).thenReturn(List.of(event("AAPL")));
        when(memory.searchForInput(eq("AAPL"), eq(3)))
                .thenReturn(List.of(new MemoryHit("id-1", "sum", "content")));

        InOrder order = inOrder(engine, memory);

        var resp = controller(engine, memory, 2000L).events(BEARER,
                Map.of("now", "2026-07-19T12:00:00Z"));

        order.verify(engine).detect(any(), any());
        order.verify(memory).searchForInput(eq("AAPL"), eq(3));

        var events = (List<Map<String, Object>>) resp.getBody().get("events");
        assertThat(events).hasSize(1);
        var priorMemory = (List<Map<String, Object>>) events.get(0).get("prior_memory");
        assertThat(priorMemory).hasSize(1);
        assertThat(priorMemory.get(0)).containsEntry("summary", "sum").containsEntry("content", "content");
    }

    @Test
    @SuppressWarnings("unchecked")
    void budgetIsSharedAcrossAllEmittedEventsInOneInvocation() {
        var engine = mock(DaywalkerEventEngine.class);
        var memory = mock(HiveMemResearchService.class);
        when(engine.detect(any(), any())).thenReturn(List.of(event("A"), event("B"), event("C")));
        when(memory.searchForInput(anyString(), eq(3))).thenAnswer(inv -> {
            Thread.sleep(300);
            return List.of();
        });

        long budgetMs = 50L;
        long startNanos = System.nanoTime();
        var resp = controller(engine, memory, budgetMs).events(BEARER,
                Map.of("now", "2026-07-19T12:00:00Z"));
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // bounded by ~one blocking call, not by 3x the black-hole sleep -- never blocks the
        // response beyond the configured budget.
        assertThat(elapsedMs).isLessThan(300 + 250);
        verify(memory, times(1)).searchForInput(anyString(), eq(3));

        var events = (List<Map<String, Object>>) resp.getBody().get("events");
        assertThat(events).hasSize(3);
        // whichever events fall after the deadline get an empty/omitted prior_memory.
        assertThat((List<Object>) events.get(1).get("prior_memory")).isEmpty();
        assertThat((List<Object>) events.get(2).get("prior_memory")).isEmpty();
    }

    @Test
    void wrongBearerReturns401AndNeverTouchesEngineOrMemory() {
        var engine = mock(DaywalkerEventEngine.class);
        var memory = mock(HiveMemResearchService.class);

        var resp = controller(engine, memory, 2000L).events("Bearer wrong",
                Map.of("now", "2026-07-19T12:00:00Z"));

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(engine, memory);
    }
}
