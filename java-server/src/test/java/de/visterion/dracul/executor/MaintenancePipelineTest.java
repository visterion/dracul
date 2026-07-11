package de.visterion.dracul.executor;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final EntryExpiryService entryExpiry = mock(EntryExpiryService.class);
    private final HardTriggerService hardTrigger = mock(HardTriggerService.class);
    private final StopRatchetService ratchet = mock(StopRatchetService.class);
    private final ExecutorIndicators indicators = mock(ExecutorIndicators.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final Tranche2Detector tranche2Detector = new Tranche2Detector();
    private final SoftConditionEvaluator softEval = new SoftConditionEvaluator();
    private final KillCriteriaEvaluator killCriteriaEvaluator = new KillCriteriaEvaluator();

    private MaintenancePipeline pipeline;

    @BeforeEach
    void setUp() {
        when(signalRepo.findPending(50)).thenReturn(List.of());
        pipeline = new MaintenancePipeline(reconcile, entryExpiry, hardTrigger, ratchet, softEval,
                indicators, positionRepo, signalRepo, tranche2Detector, killCriteriaEvaluator,
                3.0, 22, 20);
    }

    private ExecutorPosition openPosition(long id, String symbol, BigDecimal activeStop,
            BigDecimal highestPrice, BigDecimal mfeR, int softConfirmCount) {
        return openPosition(id, symbol, activeStop, highestPrice, mfeR, softConfirmCount, List.of());
    }

    private ExecutorPosition openPosition(long id, String symbol, BigDecimal activeStop,
            BigDecimal highestPrice, BigDecimal mfeR, int softConfirmCount, List<String> killCriteria) {
        return new ExecutorPosition(id, "c", symbol, "BUY", BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("95"), activeStop, 1, null, killCriteria, "sig-1", "agent",
                "2026-06-01", null, "OPEN", "brk-1", highestPrice, mfeR, softConfirmCount, null,
                null, null, null, "stop-1", null, null, null, null, 0, null, null);
    }

    @Test
    void happyPath_enrichesSurvivor() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
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
        assertThat(ep.tranche2Eligible()).isTrue();
        assertThat(ep.tranche2Reason()).isEqualTo("R_CONFIRMED");

        InOrder order = inOrder(reconcile, entryExpiry, hardTrigger, ratchet);
        order.verify(reconcile).reconcile("c", "r1");
        order.verify(entryExpiry).expire("c", "r1");
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

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
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

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
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

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
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
        assertThat(ep.tranche2Eligible()).isFalse();
        assertThat(ep.tranche2Reason()).isNull();
    }

    @Test
    void killCriteriaBreach_surfacesInEnrichedPosition() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0, List.of("close below 90"));
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("85")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.killCriteriaBreached()).containsExactly("close below 90");
    }

    @Test
    void expiryCancelledPosition_isDroppedBeforeHardTrigger() {
        // Same-pass race guard: a position the expiry step just CANCELLED in the DB must not be
        // passed on to hardTrigger.apply (it could be flattened despite having no fill).
        ExecutorPosition aaa = openPosition(2L, "AAA", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(aaa, bbb), Set.of()));
        when(entryExpiry.expire("c", "r1")).thenReturn(Set.of(2L));
        when(indicators.levels("AAA", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());
        when(indicators.levels("BBB", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());
        when(hardTrigger.apply(any(), any(), eq("r1"))).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExecutorPosition>> hardArg =
                ArgumentCaptor.forClass((Class) List.class);
        verify(hardTrigger).apply(hardArg.capture(), any(), eq("r1"));
        assertThat(hardArg.getValue()).extracting(ExecutorPosition::id).containsExactly(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("BBB");
    }

    @Test
    void unfilledPosition_excludedFromHardTriggerAndRatchet_butStillEnriched() {
        // A never-filled GTD entry (reconcile flags id 2 unfilled) holds nothing at the broker:
        // it must not reach hardTrigger.apply or ratchet.ratchet, but must still appear in the
        // enriched output so the book stays visible.
        ExecutorPosition unfilled = openPosition(2L, "AAA", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition filled = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);

        when(reconcile.reconcile("c", "r1")).thenReturn(
                new ReconcileService.ReconcileResult(List.of(unfilled, filled), Set.of(2L)));
        when(indicators.levels("AAA", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());
        when(indicators.levels("BBB", 22, 20)).thenReturn(ExecutorIndicators.Levels.unavailable());
        when(hardTrigger.apply(any(), any(), eq("r1"))).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepo.findOpen()).thenReturn(List.of(unfilled, filled));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExecutorPosition>> hardArg =
                ArgumentCaptor.forClass((Class) List.class);
        verify(hardTrigger).apply(hardArg.capture(), any(), eq("r1"));
        assertThat(hardArg.getValue()).extracting(ExecutorPosition::id).containsExactly(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExecutorPosition>> ratchetArg =
                ArgumentCaptor.forClass((Class) List.class);
        verify(ratchet).ratchet(ratchetArg.capture(), any(), eq("r1"));
        assertThat(ratchetArg.getValue()).extracting(ExecutorPosition::id).containsExactly(1L);

        assertThat(result).extracting(EnrichedPosition::symbol)
                .containsExactlyInAnyOrder("AAA", "BBB");
    }

    @Test
    void unfilledPosition_breachedKillCriterion_neverFlattenedOrClosed_realHardTrigger() {
        // End-to-end gating with a REAL HardTriggerService: kill criterion "close below 40" is
        // breached (close 39) on a position whose limit-buy entry never filled. Without the
        // unfilled gating this would flatten a non-existent broker position and fabricate a
        // CLOSED row + cooldown. It must survive untouched instead.
        de.visterion.dracul.executor.broker.FakeExecutionGateway fakeGateway =
                new de.visterion.dracul.executor.broker.FakeExecutionGateway();
        DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
        CooldownRepository cooldownRepo = mock(CooldownRepository.class);
        RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
        when(ruleVersions.active()).thenReturn("exec-v0.4");
        HardTriggerService realHardTrigger = new HardTriggerService(fakeGateway, positionRepo,
                decisionRepo, cooldownRepo, ruleVersions, new tools.jackson.databind.ObjectMapper(),
                killCriteriaEvaluator, 0.35, 1.5, 10,
                java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T12:00:00Z"),
                        java.time.ZoneOffset.UTC));
        MaintenancePipeline gatedPipeline = new MaintenancePipeline(reconcile, entryExpiry,
                realHardTrigger, ratchet, softEval, indicators, positionRepo, signalRepo,
                tranche2Detector, killCriteriaEvaluator, 3.0, 22, 20);

        ExecutorPosition unfilled = openPosition(2L, "AAA", new BigDecimal("30"),
                new BigDecimal("110"), null, 0, List.of("close below 40"));

        when(reconcile.reconcile("c", "r1")).thenReturn(
                new ReconcileService.ReconcileResult(List.of(unfilled), Set.of(2L)));
        when(indicators.levels("AAA", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("39")));
        when(positionRepo.findOpen()).thenReturn(List.of(unfilled));

        List<EnrichedPosition> result = gatedPipeline.run("c", "r1");

        assertThat(fakeGateway.flattenedSymbols).isEmpty();
        verify(positionRepo, org.mockito.Mockito.never()).close(anyLong(), any(), any(), any());
        verify(cooldownRepo, org.mockito.Mockito.never()).add(any(), any(), any(), any());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAA");
    }

    @Test
    void buyPosition_lowestPriceDecreases_writesAdverseExtreme() {
        // BUY, lowestPrice previously null (entry 100), close drops to 38 -> new adverse extreme.
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("38")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        pipeline.run("c", "r1");

        verify(positionRepo).updateAdverseExtreme(eq(1L), eq(new BigDecimal("38")));
    }

    @Test
    void buyPosition_lowestPriceAlreadyLower_doesNotWriteAdverseExtreme() {
        // BUY, lowestPrice already 39, close rises to 40 -> never a new low, no write.
        ExecutorPosition bbb = new ExecutorPosition(1L, "c", "BBB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null,
                List.of(), "sig-1", "agent", "2026-06-01", null, "OPEN", "brk-1",
                new BigDecimal("110"), new BigDecimal("1.6"), 0, null, null, null, null, "stop-1",
                null, null, null, null, 0, new BigDecimal("39"), null);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("40")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        pipeline.run("c", "r1");

        verify(positionRepo, org.mockito.Mockito.never()).updateAdverseExtreme(anyLong(), any());
    }

    @Test
    void sellPosition_neverWritesLowestPrice() {
        // SELL side: adverse extreme is the highest close, already tracked via highestPrice/ratchet.
        ExecutorPosition aaa = new ExecutorPosition(1L, "c", "AAA", "SELL", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("105"), new BigDecimal("105"), 1, null,
                List.of(), "sig-1", "agent", "2026-06-01", null, "OPEN", "brk-1",
                new BigDecimal("90"), new BigDecimal("1.6"), 0, null, null, null, null, "stop-1",
                null, null, null, null, 0, null, null);
        List<ExecutorPosition> survivors = List.of(aaa);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("AAA", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("50")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(aaa));

        pipeline.run("c", "r1");

        verify(positionRepo, org.mockito.Mockito.never()).updateAdverseExtreme(anyLong(), any());
    }

    @Test
    void tranche2Eligible_surfacesReinforcingSignal_fromPendingsFetchedOnce() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        // price 100.9 -> R = (100.9-100)/(100-95) = 0.18, no R_CONFIRMED; no entryDayHigh set.
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("100.9")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));
        when(signalRepo.findById("sig-1")).thenReturn(
                new ExecutorSignal("sig-1", "src", "v1", "BBB", "BUY", 0.8, "PEAD", List.of(), "6m",
                        null, "FILLED", "2026-06-01T00:00:00Z"));
        when(signalRepo.findPending(50)).thenReturn(List.of(
                new ExecutorSignal("s2", "src", "v1", "BBB", "BUY", 0.8, "SPIN_OFF", List.of(), "6m",
                        null, "PENDING", "2026-07-01T00:00:00Z")));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.tranche2Eligible()).isTrue();
        assertThat(ep.tranche2Reason()).isEqualTo("REINFORCING_SIGNAL");

        verify(signalRepo).findPending(50);
    }
}
