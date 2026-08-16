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
                null, null, null, "stop-1", null, null, null, null, 0, null, null,
                null, null, null, null, false);
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
        // The two maps are adjacent same-typed parameters, so a swap compiles silently. Swapped,
        // every BUY chandelier (~104) would be compared against an ATR (~2.0), safeSide would be
        // false and the ratchet would skip forever without writing a single escalation row. Pin
        // the content, not just the arity.
        order.verify(ratchet).ratchet(any(),
                eq(Map.of("BBB", new BigDecimal("2.0"))),
                eq(Map.of("BBB", new BigDecimal("108"))), eq("r1"));

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
        verify(ratchet).ratchet(ratchetArg.capture(), any(), any(), eq("r1"));
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
                        new BigDecimal("90")));
        when(hardTrigger.apply(any(), any(), eq("r1"))).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepo.findOpen()).thenReturn(List.of(bbb));

        List<EnrichedPosition> result = pipeline.run("c", "r1");

        assertThat(result).hasSize(1);
        EnrichedPosition ep = result.get(0);
        assertThat(ep.entryFilled()).isFalse();
        assertThat(ep.chandelierBreach()).isFalse();
        assertThat(ep.softConfirmCount()).isEqualTo(0);

        verify(positionRepo).updateMaintenance(eq(1L), any(), any(), eq(0), any(), any());
        verify(positionRepo, org.mockito.Mockito.never()).updateAdverseExtreme(anyLong(), any());
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
        verify(positionRepo, org.mockito.Mockito.never()).close(anyLong(), any(), any(), any(), any());
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
                null, null, null, null, 0, new BigDecimal("39"), null,
                null, null, null, null, false);
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
                null, null, null, null, 0, null, null, null, null, null, null, false);
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
                        new BigDecimal("90"), new BigDecimal("100")));
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
        // warning names distinct SYMBOLS, not positions.
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
                        new BigDecimal("90"), new BigDecimal("100")));
        when(hardTrigger.apply(eq(survivors), any(), eq("run1"))).thenReturn(survivors);
        when(positionRepo.findOpen()).thenReturn(survivors);

        var warnings = warningsWhile(MaintenancePipeline.class,
                () -> pipeline.run("c", "run1"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("maintenance indicators unavailable: 1 of 3 symbols — DARK");
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
                        new BigDecimal("90"), new BigDecimal("100")));
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
