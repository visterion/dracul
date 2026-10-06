package de.visterion.dracul.executor;

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
import static org.mockito.Mockito.doThrow;
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
    private final PendingSignalSweeper sweeper = mock(PendingSignalSweeper.class);
    private final HardTriggerService hardTrigger = mock(HardTriggerService.class);
    private final StopRatchetService ratchet = mock(StopRatchetService.class);
    private final ExecutorIndicators indicators = mock(ExecutorIndicators.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final Tranche2Detector tranche2Detector = new Tranche2Detector();
    private final SoftConditionEvaluator softEval = new SoftConditionEvaluator();
    private final SavingsPlanService savingsPlan = mock(SavingsPlanService.class);
    private final SavingsConsolidator consolidator = mock(SavingsConsolidator.class);
    private final java.time.Clock clock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-11-03T23:00:00Z"), java.time.ZoneOffset.UTC);

    private MaintenancePipeline pipeline;

    @BeforeEach
    void setUp() {
        when(signalRepo.findPending(50)).thenReturn(List.of());
        when(savingsPlan.renewLease(any())).thenReturn(true);
        pipeline = new MaintenancePipeline(reconcile, entryExpiry, sweeper, hardTrigger, ratchet,
                softEval, indicators, positionRepo, signalRepo, tranche2Detector, ConvictionProfile.defaults(), savingsPlan, consolidator, clock,
                 3.0, 22, 20);
    }

    private ExecutorPosition openPosition(long id, String symbol, BigDecimal activeStop,
            BigDecimal highestPrice, BigDecimal mfeR, int softConfirmCount) {
        return openPosition(id, symbol, activeStop, highestPrice, mfeR, softConfirmCount, List.of());
    }

    private ExecutorPosition openPosition(long id, String symbol, BigDecimal activeStop,
            BigDecimal highestPrice, BigDecimal mfeR, int softConfirmCount, List<String> killCriteria) {
        // entryFilledAt is set (broker confirmed the fill) so tranche-2 eligibility in these
        // pipeline tests is decided by price/reinforcing-signal evidence, not by this precondition.
        return ExecutorPositionFixtures.withoutKillLevel(id, "c", symbol, "BUY", BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("95"), activeStop, 1, null, killCriteria, "sig-1", "agent",
                "2026-06-01", null, "OPEN", "brk-1", highestPrice, mfeR, softConfirmCount, null,
                null, null, null, "stop-1", null, null, null, null, 0, null, null,
                null, null, null, null, false, null, "2026-07-02T00:00:00Z");
    }

    @Test
    void happyPath_enrichesSurvivor() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("108"), null));
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
        // The two maps are adjacent same-typed parameters, so a swap compiles silently. Swapped,
        // every BUY chandelier (~104) would be compared against an ATR (~2.0), safeSide would be
        // false and the ratchet would skip forever without writing a single escalation row. Pin
        // the content, not just the arity.
        order.verify(ratchet).ratchet(any(),
                eq(Map.of("BBB", new BigDecimal("2.0"))), any(), any(),
                eq(Map.of("BBB", new BigDecimal("108"))), eq("r1"));

        verify(positionRepo).updateMaintenance(eq(1L), eq(new BigDecimal("110")),
                eq(new BigDecimal("1.6")), eq(0), eq(new BigDecimal("104")), eq(null), any());
    }

    @Test
    void chandelierBreach_incrementsConfirm() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("103"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);

        ExecutorPosition bbbPostRatchet = openPosition(1L, "BBB", new BigDecimal("104"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        when(positionRepo.findOpen()).thenReturn(List.of(bbbPostRatchet));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.chandelierBreach()).isTrue();
        assertThat(ep.softConfirmCount()).isEqualTo(1);

        verify(positionRepo).updateMaintenance(eq(1L), any(), any(), eq(1), any(), any(), any());
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
                        new BigDecimal("108"), null));
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

        verify(positionRepo).updateMaintenance(eq(1L), any(), any(), eq(0), any(), any(), any());
        assertThat(ep.tranche2Eligible()).isFalse();
        assertThat(ep.tranche2Reason()).isNull();
    }

    @Test
    void killCriteriaBreach_surfacesInEnrichedPosition() {
        // Structured level only (spec 2026-10-02): the free-text entry is context, not parsed.
        ExecutorPosition bbb = ExecutorPositionFixtures.withKillLevel(openPosition(1L, "BBB",
                new BigDecimal("95"), new BigDecimal("110"), new BigDecimal("1.6"), 0,
                List.of("close below 90")), new BigDecimal("90"), null);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("85"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.killCriteriaBreached()).containsExactly("KILL_LEVEL: close 85 < kill_close_below 90");
    }

    @Test
    void freeTextKillCriterionWithoutALevel_isNeverReportedAsBreached() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0, List.of("close below 90"));
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("85"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        assertThat(pipeline.run("c", "r1").get(0).killCriteriaBreached()).isEmpty();
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
        verify(ratchet).ratchet(ratchetArg.capture(), any(), any(), any(), any(), eq("r1"));
        assertThat(ratchetArg.getValue()).extracting(ExecutorPosition::id).containsExactly(1L);

        assertThat(result).extracting(EnrichedPosition::symbol)
                .containsExactlyInAnyOrder("AAA", "BBB");
        // The enrichment carries the fill state so the agent (and Chronicle) can see it.
        assertThat(result.stream().filter(ep -> "AAA".equals(ep.symbol())).findFirst()
                .orElseThrow().entryFilled()).isFalse();
        assertThat(result.stream().filter(ep -> "BBB".equals(ep.symbol())).findFirst()
                .orElseThrow().entryFilled()).isTrue();
    }

    @Test
    void unfilledPosition_softConfirmNotAccumulated() {
        // Close 90 sits below the chandelier (110 - 3*2 = 104) AND below the entry (100) —
        // on a filled position that would increment the soft-confirm count and write a new
        // adverse extreme. Flagged UNFILLED, neither may happen: an unfilled entry has nothing
        // to soft-exit (accumulated confirms would prime an immediate exit the moment the
        // entry fills), and a pre-fill close is not an excursion of any held position.
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);

        when(reconcile.reconcile("c", "r1")).thenReturn(
                new ReconcileService.ReconcileResult(List.of(bbb), Set.of(1L)));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("90"), null));
        when(hardTrigger.apply(any(), any(), eq("r1"))).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.entryFilled()).isFalse();
        assertThat(ep.chandelierBreach()).isFalse();
        assertThat(ep.softConfirmCount()).isEqualTo(0);

        verify(positionRepo).updateMaintenance(eq(1L), any(), any(), eq(0), any(), any(), any());
        verify(positionRepo, org.mockito.Mockito.never()).updateAdverseExtreme(anyLong(), any());
    }

    @Test
    void unfilledPosition_breachedKillCriterion_neverFlattenedOrClosed_realHardTrigger() {
        // End-to-end gating with a REAL HardTriggerService: kill level 40 is
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
                0.35, 1.5, 10, mock(PartialExitService.class), ConvictionProfile.defaults(),
                mock(de.visterion.dracul.notify.TelegramNotifier.class),
                java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T12:00:00Z"),
                        java.time.ZoneOffset.UTC));
        MaintenancePipeline gatedPipeline = new MaintenancePipeline(reconcile, entryExpiry, sweeper,
                realHardTrigger, ratchet, softEval, indicators, positionRepo, signalRepo,
                tranche2Detector, ConvictionProfile.defaults(), savingsPlan, consolidator, clock,  3.0, 22, 20);

        ExecutorPosition unfilled = ExecutorPositionFixtures.withKillLevel(openPosition(2L, "AAA",
                new BigDecimal("30"), new BigDecimal("110"), null, 0, List.of("close below 40")),
                new BigDecimal("40"), null);

        when(reconcile.reconcile("c", "r1")).thenReturn(
                new ReconcileService.ReconcileResult(List.of(unfilled), Set.of(2L)));
        when(indicators.levels("AAA", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("39"), null));
        when(positionRepo.findOpen()).thenReturn(List.of(unfilled));

        List<EnrichedPosition> result = gatedPipeline.run("c", "r1");

        assertThat(fakeGateway.flattenedSymbols).isEmpty();
        verify(positionRepo, org.mockito.Mockito.never()).close(anyLong(), any(), any(), any(), any());
        verify(cooldownRepo, org.mockito.Mockito.never()).add(any(), any(), any(), any());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAA");
        // Soft-trigger context names the structured breach even though nothing was flattened.
        assertThat(result.get(0).killCriteriaBreached())
                .containsExactly("KILL_LEVEL: close 39 < kill_close_below 40");
        assertThat(result.get(0).killCloseBelow()).isEqualByComparingTo("40");
    }

    /** Spec §5 P1: the survivors reconcile hands over are REBUILT records (ReconcileService copies
     *  every OPEN row positionally each run). A level that reaches the hard trigger on such a
     *  record must flatten the position as HARD_KILL_CRITERIA with a pending exit. */
    @Test
    void rebuiltSurvivorWithABreachedLevel_isFlattenedAsHardKillCriteria_realHardTrigger() {
        de.visterion.dracul.executor.broker.FakeExecutionGateway fakeGateway =
                new de.visterion.dracul.executor.broker.FakeExecutionGateway();
        DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
        RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
        when(ruleVersions.active()).thenReturn("exec-v0.9");
        HardTriggerService realHardTrigger = new HardTriggerService(fakeGateway, positionRepo,
                decisionRepo, mock(CooldownRepository.class), ruleVersions,
                new tools.jackson.databind.ObjectMapper(), 0.35, 1.5, 3, mock(PartialExitService.class), ConvictionProfile.defaults(),
                mock(de.visterion.dracul.notify.TelegramNotifier.class),
                java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T12:00:00Z"),
                        java.time.ZoneOffset.UTC));
        MaintenancePipeline realPipeline = new MaintenancePipeline(reconcile, entryExpiry, sweeper,
                realHardTrigger, ratchet, softEval, indicators, positionRepo, signalRepo,
                tranche2Detector, ConvictionProfile.defaults(), savingsPlan, consolidator, clock,  3.0, 22, 20);

        ExecutorPosition stored = ExecutorPositionFixtures.withKillLevel(openPosition(3L, "KLV",
                new BigDecimal("30"), new BigDecimal("110"), null, 0), new BigDecimal("40"), null);
        // What ReconcileService.updateMaintenance returns: a fresh positional copy of the row.
        ExecutorPosition rebuilt = ExecutorPositionFixtures.withKillLevel(stored,
                stored.killCloseBelow(), stored.killCloseBelowDropped());
        when(reconcile.reconcile("c", "r1")).thenReturn(
                new ReconcileService.ReconcileResult(List.of(rebuilt), Set.of()));
        when(indicators.levels("KLV", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("39"), null));
        when(positionRepo.findOpen()).thenReturn(List.of());

        realPipeline.run("c", "r1");

        assertThat(fakeGateway.flattenedSymbols).containsExactly("KLV");
        verify(positionRepo).markPendingExit(eq(3L), eq("HARD_KILL_CRITERIA"), any(), any(), any());
    }

    /** Spec §5 P2: a row already carrying a pending exit has submitted its one flatten; a
     *  breached kill level on it must not flatten (or log) a second time. */
    @Test
    void pendingExitPositionWithABreachedLevel_isNotFlattenedAgain_realHardTrigger() {
        de.visterion.dracul.executor.broker.FakeExecutionGateway fakeGateway =
                new de.visterion.dracul.executor.broker.FakeExecutionGateway();
        RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
        when(ruleVersions.active()).thenReturn("exec-v0.9");
        HardTriggerService realHardTrigger = new HardTriggerService(fakeGateway, positionRepo,
                mock(DecisionLogRepository.class), mock(CooldownRepository.class), ruleVersions,
                new tools.jackson.databind.ObjectMapper(), 0.35, 1.5, 3, mock(PartialExitService.class), ConvictionProfile.defaults(),
                mock(de.visterion.dracul.notify.TelegramNotifier.class),
                java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T12:00:00Z"),
                        java.time.ZoneOffset.UTC));
        MaintenancePipeline realPipeline = new MaintenancePipeline(reconcile, entryExpiry, sweeper,
                realHardTrigger, ratchet, softEval, indicators, positionRepo, signalRepo,
                tranche2Detector, ConvictionProfile.defaults(), savingsPlan, consolidator, clock,  3.0, 22, 20);

        ExecutorPosition pending = ExecutorPositionFixtures.withKillLevel(
                ExecutorPositionFixtures.withoutKillLevel(4L, "c", "PEX", "BUY", BigDecimal.TEN,
                        new BigDecimal("100"), new BigDecimal("30"), new BigDecimal("30"), 1, null,
                        List.of(), "sig-1", "agent", "2026-06-01", null, "OPEN", "brk-1",
                        new BigDecimal("110"), null, 0, null, null, null, null, "stop-1", null,
                        null, null, null, 0, null, null, null, "HARD_STOP", "exit-1", null, false,
                        null, "2026-07-02T00:00:00Z"),
                new BigDecimal("40"), null);
        when(reconcile.reconcile("c", "r1")).thenReturn(
                new ReconcileService.ReconcileResult(List.of(pending), Set.of()));
        when(indicators.levels("PEX", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("39"), null));
        when(positionRepo.findOpen()).thenReturn(List.of(pending));

        List<EnrichedPosition> result = realPipeline.run("c", "r1");

        assertThat(fakeGateway.flattenedSymbols).isEmpty();
        verify(positionRepo, org.mockito.Mockito.never())
                .markPendingExit(anyLong(), any(), any(), any(), any());
        assertThat(result).singleElement().satisfies(ep -> assertThat(ep.killCriteriaBreached())
                .containsExactly("KILL_LEVEL: close 39 < kill_close_below 40"));
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
                        new BigDecimal("38"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        pipeline.run("c", "r1");

        verify(positionRepo).updateAdverseExtreme(eq(1L), eq(new BigDecimal("38")));
    }

    @Test
    void buyPosition_lowestPriceAlreadyLower_doesNotWriteAdverseExtreme() {
        // BUY, lowestPrice already 39, close rises to 40 -> never a new low, no write.
        ExecutorPosition bbb = ExecutorPositionFixtures.withoutKillLevel(1L, "c", "BBB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null,
                List.of(), "sig-1", "agent", "2026-06-01", null, "OPEN", "brk-1",
                new BigDecimal("110"), new BigDecimal("1.6"), 0, null, null, null, null, "stop-1",
                null, null, null, null, 0, new BigDecimal("39"), null,
                null, null, null, null, false, null, null);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("40"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        pipeline.run("c", "r1");

        verify(positionRepo, org.mockito.Mockito.never()).updateAdverseExtreme(anyLong(), any());
    }

    @Test
    void sellPosition_neverWritesLowestPrice() {
        // SELL side: adverse extreme is the highest close, already tracked via highestPrice/ratchet.
        ExecutorPosition aaa = ExecutorPositionFixtures.withoutKillLevel(1L, "c", "AAA", "SELL", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("105"), new BigDecimal("105"), 1, null,
                List.of(), "sig-1", "agent", "2026-06-01", null, "OPEN", "brk-1",
                new BigDecimal("90"), new BigDecimal("1.6"), 0, null, null, null, null, "stop-1",
                null, null, null, null, 0, null, null, null, null, null, null, false, null, null);
        List<ExecutorPosition> survivors = List.of(aaa);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("AAA", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("50"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(aaa));

        pipeline.run("c", "r1");

        verify(positionRepo, org.mockito.Mockito.never()).updateAdverseExtreme(anyLong(), any());
    }

    private List<String> warningsWhile(Class<?> loggerClass, Runnable body) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void logsOneLineNamingEverySymbolWhoseIndicatorsAreUnavailable() {
        // Two unavailable symbols (not one): with only one unavailable symbol, an
        // implementation that logged one line PER symbol would also produce exactly one line
        // here, and hasSize(1) alone could not tell the two apart.
        ExecutorPosition good = openPosition(1L, "GOOD", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition dark = openPosition(2L, "DARK", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition dark2 = openPosition(3L, "DARK2", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(good, dark, dark2);

        when(reconcile.reconcile("c", "run1")).thenReturn(
                new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels(eq("GOOD"), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"),
                        new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("2.5")));
        when(indicators.levels(eq("DARK"), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(indicators.levels(eq("DARK2"), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(hardTrigger.apply(eq(survivors), any(), eq("run1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        // Entry point is `public List<EnrichedPosition> run(String connection, String runId)`
        // (MaintenancePipeline.java:88). Stubbed the repositories the same way the existing
        // tests in this class already do so that `survivors` contains exactly GOOD, DARK, DARK2.
        var warnings = warningsWhile(MaintenancePipeline.class,
                () -> pipeline.run("c", "run1"));

        // ONE line, not one per symbol: a total outage must not produce a line per position.
        // The full message is pinned (isEqualTo, not startsWith/contains): the format — the
        // em dash, the comma join, the exact counts — is contract, not incidental wording.
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("maintenance indicators unavailable: 2 of 3 symbols — DARK,DARK2");
    }

    @Test
    void duplicatePositionsOnTheSameSymbol_reportedOnce() {
        // Two open positions on the same unavailable symbol must not double-count it — the
        // warning names distinct SYMBOLS, not positions. And n and total must count the SAME
        // thing: 3 positions on 2 distinct symbols, one of which (DARK) is fully unavailable,
        // is "1 of 2 symbols" (100% of the checked symbols), not "1 of 3" (positions) — the
        // latter would understate severity exactly when a shared symbol is the one that is down.
        ExecutorPosition dark1 = openPosition(1L, "DARK", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition dark2 = openPosition(2L, "DARK", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition good = openPosition(3L, "GOOD", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(dark1, dark2, good);

        when(reconcile.reconcile("c", "run1")).thenReturn(
                new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels(eq("DARK"), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(indicators.levels(eq("GOOD"), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"),
                        new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("2.5")));
        when(hardTrigger.apply(eq(survivors), any(), eq("run1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        var warnings = warningsWhile(MaintenancePipeline.class,
                () -> pipeline.run("c", "run1"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("maintenance indicators unavailable: 1 of 2 symbols — DARK");
    }

    @Test
    void allIndicatorsAvailable_logsNothing() {
        // The counterpart property to the outage line: when nothing is unavailable, the run
        // must stay silent. This is the difference between a usable alarm signal and daily noise.
        ExecutorPosition good = openPosition(1L, "GOOD", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(good);

        when(reconcile.reconcile("c", "run1")).thenReturn(
                new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels(eq("GOOD"), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"),
                        new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("2.5")));
        when(hardTrigger.apply(eq(survivors), any(), eq("run1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        var warnings = warningsWhile(MaintenancePipeline.class,
                () -> pipeline.run("c", "run1"));

        assertThat(warnings).isEmpty();
    }

    @Test
    void unfilledPositionWithUnavailableIndicators_notCountedOrNamedInWarning() {
        // A position whose GTD entry never filled is excluded from hard-trigger/ratchet below
        // regardless of indicator availability (see MaintenancePipeline.java run(), the
        // filledSurvivors gating) — no safety check was ever going to run on it this pass, so a
        // missing indicator for it is not a skipped check and must not inflate the warning: not
        // in the numerator, not in the denominator, not named.
        ExecutorPosition dark = openPosition(1L, "DARK", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition unfilled = openPosition(2L, "OTHER", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(dark, unfilled);

        when(reconcile.reconcile("c", "run1")).thenReturn(
                new ReconcileService.ReconcileResult(survivors, Set.of(2L)));
        when(indicators.levels(eq("DARK"), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(indicators.levels(eq("OTHER"), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(hardTrigger.apply(any(), any(), eq("run1"))).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepo.findOpen()).thenReturn(survivors);

        var warnings = warningsWhile(MaintenancePipeline.class,
                () -> pipeline.run("c", "run1"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("maintenance indicators unavailable: 1 of 1 symbols — DARK");
    }

    @Test
    void pendingExitPositionWithUnavailableIndicators_notCountedOrNamedInWarning() {
        // Mirrors the unfilled-position case above for the OTHER half of the same restriction
        // (MaintenancePipeline.java run(): `p.pendingExitReason() == null`). A position already
        // carrying a pendingExitReason has already submitted its one flatten/close order for
        // this exit and is excluded from hard-trigger/ratchet just like an unfilled entry — so a
        // missing indicator for it is likewise not a skipped check. Without this test, removing
        // the `|| p.pendingExitReason() != null` half of the restriction would stay green.
        ExecutorPosition dark = openPosition(1L, "DARK", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition pendingExit = ExecutorPositionFixtures.withoutKillLevel(2L, "c", "OTHER", "BUY",
                BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"),
                1, null, List.of(), "sig-1", "agent", "2026-06-01", null, "OPEN", "brk-1",
                new BigDecimal("110"), new BigDecimal("1.6"), 0, null, null, null, null,
                "stop-1", null, null, null, null, 0, null, null, null,
                "HARD_STOP", null, null, false, null, null);
        List<ExecutorPosition> survivors = List.of(dark, pendingExit);

        when(reconcile.reconcile("c", "run1")).thenReturn(
                new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels(eq("DARK"), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(indicators.levels(eq("OTHER"), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());
        when(hardTrigger.apply(any(), any(), eq("run1"))).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepo.findOpen()).thenReturn(survivors);

        var warnings = warningsWhile(MaintenancePipeline.class,
                () -> pipeline.run("c", "run1"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("maintenance indicators unavailable: 1 of 1 symbols — DARK");
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
                        new BigDecimal("100.9"), null));
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

    /** Test 25. The ratchet gets atrEff; the soft trigger and the LLM view keep ATR22. Widening
     *  the soft trigger would silence it — it is MEANT to fire earlier than the hard stop.
     *  Mutation: build one map and pass it to both. */
    @Test
    void ratchetReceivesAtrEffWhileEnrichKeepsAtr22() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1"))
                .thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("108"), new BigDecimal("5.0")));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        verify(ratchet).ratchet(any(),
                eq(Map.of("BBB", new BigDecimal("2.0"))),    // atr22
                eq(Map.of("BBB", new BigDecimal("5.0"))),    // atr_short
                eq(Map.of("BBB", new BigDecimal("5.0"))),    // atrEff = max(2.0, 5.0)
                eq(Map.of("BBB", new BigDecimal("108"))),    // closes
                eq("r1"));

        // enrich() and the LLM view stay on ATR22: chandelier 110 - 3 * 2.0 = 104, NOT 110 - 15.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).atr()).isEqualByComparingTo("2.0");
        assertThat(result.get(0).chandelierLevel()).isEqualByComparingTo("104");
        assertThat(result.get(0).atrShort()).isEqualByComparingTo("5.0");
    }

    /** The fail-soft atr_short line: a symbol whose short window has too few bars is DATA, not an
     *  outage — one warn line per RUN naming the symbol, and atrEff quietly falls back to ATR22 so
     *  the ratchet still runs on that symbol.
     *  Mutation: delete the warn block (no line at all), or log per position rather than per
     *  symbol (GOOD carries two positions, so that yields two lines). */
    @Test
    void missingShortAtrIsWarnedOncePerSymbolAndAtrEffFallsBackToAtr22() {
        ExecutorPosition good1 = openPosition(1L, "GOOD", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition good2 = openPosition(2L, "GOOD", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        ExecutorPosition othr = openPosition(3L, "OTHR", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(good1, good2, othr);

        when(reconcile.reconcile("c", "run1")).thenReturn(
                new ReconcileService.ReconcileResult(survivors, Set.of()));
        // Available in every other respect — only the short window is missing.
        when(indicators.levels(eq("GOOD"), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"),
                        new BigDecimal("90"), new BigDecimal("100"), null));
        when(indicators.levels(eq("OTHR"), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"),
                        new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("5.0")));
        when(hardTrigger.apply(eq(survivors), any(), eq("run1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        var warnings = warningsWhile(MaintenancePipeline.class,
                () -> pipeline.run("c", "run1"));

        // Exactly one line, distinguishable from the outage line by naming the indicator, and
        // pinned in full: the counts and the comma join are contract, not incidental wording.
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("maintenance indicators unavailable: atr_short for 1 of 2 symbols — GOOD");

        // Fail-soft: GOOD is still ratcheted, on ATR22 as its effective ATR.
        verify(ratchet).ratchet(any(),
                eq(Map.of("GOOD", new BigDecimal("2.0"), "OTHR", new BigDecimal("2.0"))),
                eq(Map.of("OTHR", new BigDecimal("5.0"))),
                eq(Map.of("GOOD", new BigDecimal("2.0"), "OTHR", new BigDecimal("5.0"))),
                eq(Map.of("GOOD", new BigDecimal("100"), "OTHR", new BigDecimal("100"))),
                eq("run1"));
    }

    /** The sweep runs INSIDE the maintenance pass, after the GTD-entry expiry and before the
     *  pipeline reads its own pending set at findPending(50) — so a PENDING signal past the age
     *  bound stops conferring REINFORCING_SIGNAL tranche-2 eligibility in the SAME pass
     *  (Tranche2Detector has no age filter of its own). */
    @Test
    void sweepRunsAfterEntryExpiryAndBeforeThePendingRead() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1"))
                .thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("108"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        pipeline.run("c", "r1");

        InOrder order = inOrder(entryExpiry, sweeper, signalRepo);
        order.verify(entryExpiry).expire("c", "r1");
        order.verify(sweeper).sweep("r1");
        order.verify(signalRepo).findPending(50);
    }

    /** I1: the sweep is now guarded so a hygiene failure can never suppress the deterministic
     *  hard-exit/ratchet steps that follow it. Before the fix, an exception out of
     *  sweeper.sweep(...) (e.g. an unmappable PENDING row surfacing from findPending inside the
     *  sweeper) would propagate out of run(), and hardTrigger.apply/ratchet.ratchet would never
     *  be invoked at all. */
    @Test
    void sweepFailure_doesNotAbortHardTriggerAndRatchet() {
        ExecutorPosition bbb = openPosition(1L, "BBB", new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("1.6"), 0);
        List<ExecutorPosition> survivors = List.of(bbb);

        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("108"), null));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(sweeper).sweep("r1");
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        InOrder order = inOrder(entryExpiry, sweeper, hardTrigger, ratchet);
        order.verify(entryExpiry).expire("c", "r1");
        order.verify(sweeper).sweep("r1");
        order.verify(hardTrigger).apply(any(), any(), eq("r1"));
        order.verify(ratchet).ratchet(any(), any(), any(), any(), any(), eq("r1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("BBB");
    }

    /** Spec 2026-10-03 §5.6: no soft trigger for CONVICTION — no chandelier level, no confirm
     *  creep, whatever the close does. */
    @Test
    void convictionPositionsCarryNoSoftTrigger() {
        // A kill level 110 above the close 103 would put a STANDARD row's breach into the soft
        // trigger context; CONVICTION ignores kill levels, so it must stay empty too.
        ExecutorPosition p = ExecutorPositionFixtures.withKillLevel(
                ExecutorPositionFixtures.conviction(openPosition(1L, "BBB",
                        new BigDecimal("65"), new BigDecimal("110"), new BigDecimal("1.6"), 1)),
                new BigDecimal("110"), null);
        List<ExecutorPosition> survivors = List.of(p);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("103"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).singleElement().satisfies(e -> {
            assertThat(e.exitProfile()).isEqualTo(ExitProfile.CONVICTION);
            assertThat(e.chandelierLevel()).isNull();
            assertThat(e.chandelierBreach()).isFalse();
            assertThat(e.softConfirmCount()).isZero();
            assertThat(e.killCriteriaBreached()).isEmpty();
            assertThat(e.tranche2Eligible()).isFalse();
        });
        verify(positionRepo).updateMaintenance(eq(1L), any(), any(), eq(0), any(), any(), any());
    }

    /** Spec 2026-10-04 §3: MOMENTUM is a wide-stop profile — no soft trigger, no kill level. */
    @Test
    void momentumPositionsCarryNoSoftTrigger() {
        ExecutorPosition p = ExecutorPositionFixtures.withKillLevel(
                ExecutorPositionFixtures.withProfileFields(openPosition(1L, "BBB",
                        new BigDecimal("65"), new BigDecimal("110"), new BigDecimal("1.6"), 1),
                        ExitProfile.MOMENTUM, null, null, null, false),
                new BigDecimal("110"), null);
        List<ExecutorPosition> survivors = List.of(p);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("BBB", 22, 20))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("2.0"), null,
                        new BigDecimal("103"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).singleElement().satisfies(e -> {
            assertThat(e.exitProfile()).isEqualTo(ExitProfile.MOMENTUM);
            assertThat(e.chandelierLevel()).isNull();
            assertThat(e.chandelierBreach()).isFalse();
            assertThat(e.softConfirmCount()).isZero();
            assertThat(e.killCriteriaBreached()).isEmpty();
            assertThat(e.tranche2Eligible()).isFalse();
        });
    }

    /** Spec 2026-10-06 §5.2: the enriched R of a CONVICTION row uses entry × 0.35. */
    @Test
    void convictionRCurrentUsesTheAverageRisk() {
        ExecutorPosition p = ExecutorPositionFixtures.conviction(ExecutorPositionFixtures.withoutKillLevel(
                1L, "c", "TECHC", "BUY", BigDecimal.TEN, new BigDecimal("60"), new BigDecimal("65"),
                new BigDecimal("39.00"), 1, null, List.of(), "sig-1", "agent", "2026-06-01", null,
                "OPEN", "brk-1", new BigDecimal("60"), null, 0, null, null, null, null, "stop-1",
                null, null, null, null, 0, null, null, null, null, null, null, false, null,
                "2026-07-02T00:00:00Z"));
        List<ExecutorPosition> survivors = List.of(p);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(survivors, Set.of()));
        when(indicators.levels("TECHC", 22, 20)).thenReturn(new ExecutorIndicators.Levels(true,
                new BigDecimal("2.0"), null, new BigDecimal("50"), null));
        when(hardTrigger.apply(eq(survivors), any(), eq("r1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).singleElement()
                .satisfies(e -> assertThat(e.rCurrent()).isEqualByComparingTo("-0.476190"));
    }

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> pipelineLog() {
        var a = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        a.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MaintenancePipeline.class)).addAppender(a);
        return a;
    }

    @org.junit.jupiter.api.AfterEach
    void detachPipelineLog() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MaintenancePipeline.class))
                .detachAndStopAllAppenders();
    }

    private static List<String> lines(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> a, String prefix) {
        return a.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix)).toList();
    }

    /** Every symbol has indicators (the levels loop dereferences the Levels record). */
    private void anyLevels() {
        when(indicators.levels(any(), eq(22), eq(20))).thenReturn(new ExecutorIndicators.Levels(true,
                new BigDecimal("2.0"), null, new BigDecimal("108"), null));
    }

    /** Spec 2026-10-06 §6.2: an in-flight add keeps its position out of hard triggers and the ratchet,
     *  but the position stays in the enriched book. */
    @Test
    void inFlightPositionsAreExcludedFromTriggersAndRatchetButStayVisible() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition inFlight = openPosition(1L, "TECHA", new BigDecimal("65"), new BigDecimal("110"), null, 0);
        ExecutorPosition other = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(inFlight, other), Set.of()));
        when(savingsPlan.inFlightStatuses()).thenReturn(Map.of(1L, SavingsBuy.PLACED));
        when(hardTrigger.apply(eq(List.of(other)), any(), eq("r1"))).thenReturn(List.of(other));
        when(positionRepo.findOpen()).thenReturn(List.of(inFlight, other));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        verify(hardTrigger).apply(eq(List.of(other)), any(), eq("r1"));
        verify(ratchet).ratchet(eq(List.of(other)), any(), any(), any(), any(), eq("r1"));
        assertThat(result).extracting(EnrichedPosition::symbol).containsExactlyInAnyOrder("TECHA", "BBB");
        assertThat(lines(log, "savings-plan exclusion")).singleElement().satisfies(l -> assertThat(l)
                .contains("excluded=[1:TECHA:PLACED]").contains("stop_only=[]"));
    }

    /** §6.2 / R4 Minor 2: UNPROTECTED is not excluded — stop breach and catastrophe only, no
     *  TARGET_HALF (stop-only set) and no ratchet. */
    @Test
    void unprotectedPositionsGetOnlyTheStopAndNoRatchet() {
        anyLevels();
        ExecutorPosition unprotected = openPosition(1L, "TECHA", new BigDecimal("65"), new BigDecimal("110"), null, 0);
        ExecutorPosition other = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        List<ExecutorPosition> both = List.of(unprotected, other);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(both, Set.of()));
        when(savingsPlan.inFlightStatuses()).thenReturn(Map.of(1L, SavingsBuy.UNPROTECTED));
        when(hardTrigger.apply(eq(both), any(), eq("r1"), eq(Set.of(1L)))).thenReturn(both);
        when(positionRepo.findOpen()).thenReturn(both);

        pipeline.run("c", "r1");

        verify(hardTrigger).apply(eq(both), any(), eq("r1"), eq(Set.of(1L)));
        verify(ratchet).ratchet(eq(List.of(other)), any(), any(), any(), any(), eq("r1"));
    }

    /** §3.2: reconcile → … → CONSOLIDATE → triggers → ratchet → STOP_NOT_LIVE → SAVINGS_ADD → release (final review I1). */
    @Test
    void consolidationRunsBeforeTheTriggersAndTheAddAfterThem() {
        anyLevels();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        InOrder order = inOrder(reconcile, consolidator, hardTrigger, ratchet, savingsPlan);
        order.verify(reconcile).reconcile("c", "r1");
        order.verify(savingsPlan).tryLease(any());
        order.verify(consolidator).consolidateStage(eq("c"), eq("r1"), any(), any());
        order.verify(savingsPlan).staleCheck(eq("c"), eq("r1"), any(), any());
        order.verify(savingsPlan).positionsTouchedSince(any());
        order.verify(savingsPlan).inFlightStatuses();
        order.verify(hardTrigger).apply(any(), any(), eq("r1"));
        order.verify(ratchet).ratchet(any(), any(), any(), any(), any(), eq("r1"));
        order.verify(savingsPlan).renewLease(any());
        order.verify(savingsPlan).checkStopsLive(eq("c"), eq("r1"), any());
        order.verify(savingsPlan).addStage(eq("c"), eq("r1"), any(), any(), any());
        order.verify(savingsPlan).releaseLease(any());
    }

    /** Final review I1: STOP_NOT_LIVE runs BEFORE the add and its not-live positions reach the add
     *  stage — an add must never put a position with a dead leg-1 stop in flight. */
    @Test
    void theStopLiveResultIsHandedToTheAdd() {
        anyLevels();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        when(savingsPlan.renewLease(any())).thenReturn(true);
        when(savingsPlan.checkStopsLive(any(), any(), any())).thenReturn(Map.of(2L, "notworking"));
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        verify(savingsPlan).addStage(eq("c"), eq("r1"), any(), any(), eq(Map.of(2L, "notworking")));
    }

    /** Final review I1: a failed stop-live check hands "unknown" (null) to the add — fail closed. */
    @Test
    void aFailedStopLiveCheckHandsUnknownToTheAdd() {
        anyLevels();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        when(savingsPlan.renewLease(any())).thenReturn(true);
        when(savingsPlan.checkStopsLive(any(), any(), any())).thenThrow(new IllegalStateException("synthetic"));
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        verify(savingsPlan).addStage(eq("c"), eq("r1"), any(), any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void aSavingsStageExceptionStillRunsTheTriggersAndReleasesTheLease() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        when(consolidator.consolidateStage(any(), any(), any(), any())).thenThrow(new IllegalStateException("synthetic"));
        when(savingsPlan.addStage(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("synthetic add"));
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        verify(hardTrigger).apply(any(), any(), eq("r1"));
        verify(savingsPlan).releaseLease(any());
        assertThat(log.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=consolidate"))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=add"));
    }

    /** §12: a 15:00 UTC pass does nothing new — and says why for each skipped stage. */
    @Test
    void aPassOutsideTheWindowDoesNothingNewAndSaysWhy() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        verify(savingsPlan, org.mockito.Mockito.never()).tryLease(any());
        verify(consolidator, org.mockito.Mockito.never()).consolidateStage(any(), any(), any(), any());
        verify(savingsPlan, org.mockito.Mockito.never()).addStage(any(), any(), any(), any(), any());
        verify(savingsPlan).staleCheck(eq("c"), eq("r1"), any(), any());
        assertThat(lines(log, "savings-plan stage")).hasSize(3).allSatisfy(l -> assertThat(l)
                .contains("outcome=did-nothing").contains("why=outside-window"));
    }

    @Test
    void aPassWithoutTheLeaseSkipsTheStagesAndNamesTheHolder() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(false);
        when(savingsPlan.leaseHolder()).thenReturn("other-pass-uuid");
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        verify(consolidator, org.mockito.Mockito.never()).consolidateStage(any(), any(), any(), any());
        verify(savingsPlan, org.mockito.Mockito.never()).addStage(any(), any(), any(), any(), any());
        verify(savingsPlan, org.mockito.Mockito.never()).releaseLease(any());
        verify(savingsPlan).staleCheck(eq("c"), eq("r1"), any(), any());
        verify(savingsPlan).positionsTouchedSince(any());
        assertThat(lines(log, "savings-plan stage")).hasSize(3).allSatisfy(l -> assertThat(l)
                .contains("why=lease-held-by:other-pass-uuid"));
    }

    /** §3.4 / R3 M6: the lease holder is a fresh UUID per run() — never the (reused) run id. */
    @Test
    void everyRunUsesAFreshPassUuidEvenWithTheSameRunId() {
        anyLevels();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");
        pipeline.run("c", "r1");

        ArgumentCaptor<String> passes = ArgumentCaptor.forClass(String.class);
        verify(savingsPlan, org.mockito.Mockito.times(2)).tryLease(passes.capture());
        assertThat(passes.getAllValues()).doesNotHaveDuplicates().doesNotContain("r1")
                .allSatisfy(s -> assertThat(java.util.UUID.fromString(s)).isNotNull());
    }

    /** §3.2: a survivor whose savings row changed this pass is re-read from the DB before the triggers. */
    @Test
    void aTouchedSurvivorIsReReadBeforeTheTriggers() {
        anyLevels();
        ExecutorPosition stale = openPosition(1L, "TECHA", new BigDecimal("65"), new BigDecimal("110"), null, 0);
        ExecutorPosition fresh = openPosition(1L, "TECHA", new BigDecimal("68.76"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(stale), Set.of()));
        when(savingsPlan.positionsTouchedSince(any())).thenReturn(Set.of(1L));
        when(positionRepo.findById(1L)).thenReturn(fresh);
        when(hardTrigger.apply(eq(List.of(fresh)), any(), eq("r1"))).thenReturn(List.of(fresh));
        when(positionRepo.findOpen()).thenReturn(List.of(fresh));

        pipeline.run("c", "r1");

        verify(hardTrigger).apply(eq(List.of(fresh)), any(), eq("r1"));
    }

    /** §8a: the exclusion line is written once per pass — also when nothing is in flight. */
    @Test
    void theExclusionLineIsWrittenOncePerPassEvenWithNothingInFlight() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        assertThat(lines(log, "savings-plan exclusion")).singleElement().satisfies(l -> assertThat(l)
                .contains("excluded=[]").contains("stop_only=[]").contains("reason=nothing-in-flight"));
    }

    /** §3.3 / Task 5: the savings stages key on time, never on the run id — a null run id is harmless. */
    @Test
    void aNullRunIdIsHarmless() {
        anyLevels();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", null)).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        when(hardTrigger.apply(eq(List.of(p)), any(), org.mockito.ArgumentMatchers.isNull())).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<EnrichedPosition> result = pipeline.run("c", null);

        assertThat(result).hasSize(1);
        verify(consolidator).consolidateStage(eq("c"), org.mockito.ArgumentMatchers.isNull(), any(), any());
        verify(savingsPlan).addStage(eq("c"), org.mockito.ArgumentMatchers.isNull(), any(), any(), any());
        verify(savingsPlan).releaseLease(any());
    }

    // ---- fix round 1: no savings read or lease call may keep the hard triggers from running ----

    private ExecutorPosition convictionPosition(long id, String symbol) {
        return ExecutorPositionFixtures.conviction(
                openPosition(id, symbol, new BigDecimal("65"), new BigDecimal("110"), null, 0));
    }

    private static List<String> warns(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> a) {
        return a.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
    }

    /** In-flight state unreadable: every OPEN CONVICTION survivor becomes stop-only (stop + catastrophe,
     *  no TARGET_HALF, no ratchet); other profiles are untouched. */
    @Test
    void anUnreadableInFlightStateMakesEveryConvictionSurvivorStopOnly() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition conv = convictionPosition(1L, "TECHA");
        ExecutorPosition std = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        List<ExecutorPosition> both = List.of(conv, std);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(both, Set.of()));
        when(savingsPlan.inFlightStatuses()).thenThrow(new IllegalStateException("synthetic db down"));
        when(hardTrigger.apply(eq(both), any(), eq("r1"), eq(Set.of(1L)))).thenReturn(both);
        when(positionRepo.findOpen()).thenReturn(both);

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        verify(hardTrigger).apply(eq(both), any(), eq("r1"), eq(Set.of(1L)));
        verify(ratchet).ratchet(eq(List.of(std)), any(), any(), any(), any(), eq("r1"));
        assertThat(result).hasSize(2);
        assertThat(warns(log)).anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=exclusion"));
        assertThat(lines(log, "savings-plan exclusion")).singleElement().satisfies(l -> assertThat(l)
                .contains("stop_only=[1:TECHA:UNPROTECTED]").contains("reason=in-flight-unknown"));
    }

    /** A legacy pass (no savings rows, no CONVICTION) whose savings repo is down still runs the triggers
     *  on the in-memory book, unchanged. */
    @Test
    void aLegacyPassStillRunsTheTriggersWhenTheSavingsRepoThrows() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        RuntimeException down = new IllegalStateException("synthetic db down");
        when(savingsPlan.inWindow(any())).thenThrow(down);
        doThrow(down).when(savingsPlan).staleCheck(any(), any(), any(), any());
        when(savingsPlan.positionsTouchedSince(any())).thenThrow(down);
        when(savingsPlan.inFlightStatuses()).thenThrow(down);
        when(positionRepo.findById(2L)).thenThrow(down);
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        verify(hardTrigger).apply(eq(List.of(p)), any(), eq("r1"));
        verify(ratchet).ratchet(eq(List.of(p)), any(), any(), any(), any(), eq("r1"));
        assertThat(result).hasSize(1);
        assertThat(warns(log))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=lease"))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=stale"))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=reread"))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan row failed stage=reread position=2"))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=exclusion"));
    }

    /** The touched set unreadable: every survivor is re-read; one failing re-read keeps its in-memory row. */
    @Test
    void anUnreadableTouchedSetReReadsEverySurvivorAndKeepsAFailingOne() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition staleA = openPosition(1L, "AAA", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        ExecutorPosition freshA = openPosition(1L, "AAA", new BigDecimal("96"), new BigDecimal("110"), null, 0);
        ExecutorPosition b = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(staleA, b), Set.of()));
        when(savingsPlan.positionsTouchedSince(any())).thenThrow(new IllegalStateException("synthetic"));
        when(positionRepo.findById(1L)).thenReturn(freshA);
        when(positionRepo.findById(2L)).thenThrow(new IllegalStateException("synthetic row"));
        when(hardTrigger.apply(eq(List.of(freshA, b)), any(), eq("r1"))).thenReturn(List.of(freshA, b));
        when(positionRepo.findOpen()).thenReturn(List.of(freshA, b));

        pipeline.run("c", "r1");

        verify(hardTrigger).apply(eq(List.of(freshA, b)), any(), eq("r1"));
        assertThat(warns(log))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=reread"))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan row failed stage=reread position=2"));
    }

    @Test
    void aThrowingLeaseAcquireStillRunsTheTriggersAndNeverReleases() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenThrow(new IllegalStateException("synthetic"));
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        assertThat(pipeline.run("c", "r1")).hasSize(1);

        verify(hardTrigger).apply(eq(List.of(p)), any(), eq("r1"));
        verify(consolidator, org.mockito.Mockito.never()).consolidateStage(any(), any(), any(), any());
        verify(savingsPlan, org.mockito.Mockito.never()).releaseLease(any());
        assertThat(lines(log, "savings-plan stage stage=")).hasSize(3)
                .allSatisfy(l -> assertThat(l).contains("why=lease-error"));
    }

    @Test
    void aThrowingReleaseNeverMasksThePassResult() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        doThrow(new IllegalStateException("synthetic")).when(savingsPlan).releaseLease(any());
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        assertThat(pipeline.run("c", "r1")).hasSize(1);

        assertThat(warns(log)).anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=release"));
    }

    /** The lease is renewed before the add; a lost lease skips add and stop-live, still releases nothing
     *  it does not hold (release is holder-scoped). */
    @Test
    void aLostLeaseBeforeTheAddSkipsTheRemainingStages() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        when(savingsPlan.renewLease(any())).thenReturn(false);
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        verify(consolidator).consolidateStage(eq("c"), eq("r1"), any(), any());
        verify(savingsPlan, org.mockito.Mockito.never()).addStage(any(), any(), any(), any(), any());
        verify(savingsPlan, org.mockito.Mockito.never()).checkStopsLive(any(), any(), any());
        assertThat(lines(log, "savings-plan stage stage=")).hasSize(2)
                .allSatisfy(l -> assertThat(l).contains("why=lease-lost"));
    }

    @Test
    void aHolderThatExpiredMeanwhileIsNeverLoggedAsNull() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(false);
        when(savingsPlan.leaseHolder()).thenReturn(null);
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        pipeline.run("c", "r1");

        assertThat(lines(log, "savings-plan stage stage=")).hasSize(3)
                .allSatisfy(l -> assertThat(l).contains("why=lease-expired-race").doesNotContain("null"));
    }

    @Test
    void throwingStaleAndStopLiveChecksAreGuarded() {
        anyLevels();
        var log = pipelineLog();
        ExecutorPosition p = openPosition(2L, "BBB", new BigDecimal("95"), new BigDecimal("110"), null, 0);
        when(reconcile.reconcile("c", "r1")).thenReturn(new ReconcileService.ReconcileResult(List.of(p), Set.of()));
        when(savingsPlan.inWindow(any())).thenReturn(true);
        when(savingsPlan.tryLease(any())).thenReturn(true);
        doThrow(new IllegalStateException("synthetic")).when(savingsPlan).staleCheck(any(), any(), any(), any());
        when(savingsPlan.checkStopsLive(any(), any(), any())).thenThrow(new IllegalStateException("synthetic"));
        when(hardTrigger.apply(eq(List.of(p)), any(), eq("r1"))).thenReturn(List.of(p));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        assertThat(pipeline.run("c", "r1")).hasSize(1);

        verify(hardTrigger).apply(eq(List.of(p)), any(), eq("r1"));
        verify(savingsPlan).releaseLease(any());
        assertThat(warns(log))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=stale"))
                .anySatisfy(m -> assertThat(m).startsWith("savings-plan stage failed stage=stop-live"));
    }
}
