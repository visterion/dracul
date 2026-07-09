package de.visterion.dracul.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the server-side maintenance orchestration order — reconcile, then hard-trigger,
 *  then ratchet, then a fresh re-read intersected with hard-trigger survivors — and that the
 *  soft-confirm count is persisted every pass, with nulls handled safely when indicators are
 *  unavailable for a symbol. */
class MaintenancePipelineTest {

    private final ReconcileService reconcile = mock(ReconcileService.class);
    private final HardTriggerService hardTrigger = mock(HardTriggerService.class);
    private final StopRatchetService ratchet = mock(StopRatchetService.class);
    private final ExecutorIndicators indicators = mock(ExecutorIndicators.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final SoftConditionEvaluator softEval = new SoftConditionEvaluator();

    private MaintenancePipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new MaintenancePipeline(reconcile, hardTrigger, ratchet, softEval, indicators,
                positionRepo, 3.0, 22, 20);
    }

    private ExecutorPosition openPosition(long id, String symbol, BigDecimal activeStop,
            BigDecimal highestPrice, BigDecimal mfeR, int softConfirmCount) {
        return new ExecutorPosition(id, "c", symbol, "BUY", BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("95"), activeStop, 1, null, List.of(), "sig-1", "agent",
                "2026-06-01", null, "OPEN", "brk-1", highestPrice, mfeR, softConfirmCount, null,
                null, null, null, "stop-1");
    }

    @Test
    void happyPath_enrichesSurvivor() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(survivors);
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("108")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);

        ExecutorPosition bbbPostRatchet = openPosition(1L, "BBB", new BigDecimal("104"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(bbbPostRatchet));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.symbol()).isEqualTo("BBB");
        assertThat(ep.currentPrice()).isEqualByComparingTo("108");
        assertThat(ep.atr()).isEqualByComparingTo("2.0");
        assertThat(ep.chandelierLevel()).isEqualByComparingTo("104");
        assertThat(ep.rCurrent()).isEqualByComparingTo("1.6");
        assertThat(ep.mfeR()).isEqualByComparingTo("1.6");
        assertThat(ep.chandelierBreach()).isFalse();
        assertThat(ep.softConfirmCount()).isEqualTo(0);

        InOrder order = inOrder(reconcile, hardTrigger, ratchet);
        order.verify(reconcile).reconcile("c", "r1");
        order.verify(hardTrigger).apply(any(), any(), eq("r1"));
        order.verify(ratchet).ratchet(any(), any(), eq("r1"));

        verify(positionRepo).updateMaintenance(eq(1L), eq(new BigDecimal("110")),
                eq(new BigDecimal("1.6")), eq(0), eq(new BigDecimal("104")), eq(null));
    }

    @Test
    void chandelierBreach_incrementsConfirm() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(survivors);
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("103")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);

        ExecutorPosition bbbPostRatchet = openPosition(1L, "BBB", new BigDecimal("104"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(bbbPostRatchet));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.chandelierBreach()).isTrue();
        assertThat(ep.softConfirmCount()).isEqualTo(1);

        verify(positionRepo).updateMaintenance(eq(1L), any(), any(), eq(1), any(), any());
    }

    @Test
    void hardClosed_excludedFromEnriched() {
        ExecutorPosition aaa = openPosition(2L, "AAA", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(aaa, bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(survivors);
        when(indicators.levels("AAA", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("108")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(List.of(bbb));

        ExecutorPosition bbbPostRatchet = openPosition(1L, "BBB", new BigDecimal("104"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(bbbPostRatchet));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("BBB");
    }

    @Test
    void indicatorsUnavailable_survivesWithNulls() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(survivors);
        when(indicators.levels("BBB", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.currentPrice()).isNull();
        assertThat(ep.atr()).isNull();
        assertThat(ep.chandelierLevel()).isNull();
        assertThat(ep.rCurrent()).isNull();
        assertThat(ep.chandelierBreach()).isFalse();
        assertThat(ep.softConfirmCount()).isEqualTo(0);

        verify(positionRepo).updateMaintenance(eq(1L), any(), any(), eq(0), any(), any());
    }
}
