package de.visterion.dracul.outcome;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.research.ResearchMemoryLink;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit test for {@link OutcomeMemoryScanJob} — mirrors {@code OutcomeBatchJobTest}'s
 * posture (independent of Spring/Testcontainers; see the Task 9 report for why the
 * {@code @SpringBootTest} Testcontainers context could not be exercised here).
 *
 * <p>The {@link ResearchMemoryLinkRepository} mock is backed by a small in-memory model so
 * {@code markOutcomeWritten}/{@code markExcluded} actually flip the row out of
 * {@code findUnwrittenPreyLinks} across repeated {@code scanOnce()} calls, the way the real
 * repository does — needed to test retry/idempotency/exclusion behavior across scans.
 */
class OutcomeScanMemoryTest {

    private final ResearchMemoryLinkRepository links = mock(ResearchMemoryLinkRepository.class);
    private final ExecutorPositionRepository positions = mock(ExecutorPositionRepository.class);
    private final ExecutorSignalRepository signals = mock(ExecutorSignalRepository.class);
    private final OutcomeLogRepository outcomeLog = mock(OutcomeLogRepository.class);
    private final HiveMemResearchService memory = mock(HiveMemResearchService.class);

    private final OutcomeMemoryScanJob job =
            new OutcomeMemoryScanJob(links, positions, signals, outcomeLog, memory, 120);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(OutcomeMemoryScanJob.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** Backs {@code links} with an in-memory list so outcome_written/excluded state persists
     *  realistically across repeated {@code scanOnce()} invocations within one test. */
    private void backLinksWith(List<ResearchMemoryLink> all) {
        List<ResearchMemoryLink> rows = new ArrayList<>(all);
        Set<Long> written = new HashSet<>();

        when(links.findUnwrittenPreyLinks(anyInt())).thenAnswer(inv ->
                rows.stream().filter(l -> !written.contains(l.id())).toList());
        doAnswer(inv -> {
            written.add((Long) inv.getArgument(0));
            return null;
        }).when(links).markOutcomeWritten(anyLong());
        doAnswer(inv -> {
            written.add((Long) inv.getArgument(0));
            return null;
        }).when(links).markExcluded(anyLong());
    }

    private static ResearchMemoryLink link(long id, String preyId, String symbol, String cellId) {
        return new ResearchMemoryLink(id, "prey", preyId, symbol, cellId, Instant.now(), false);
    }

    private static ResearchMemoryLink link(long id, String preyId, String symbol, String cellId,
            Instant createdAt) {
        return new ResearchMemoryLink(id, "prey", preyId, symbol, cellId, createdAt, false);
    }

    private static ExecutorSignal signal(String signalId, String symbol, String preyId) {
        return new ExecutorSignal(signalId, "strigoi-spin", "v1", symbol, "BUY", 0.7, "SPINOFF",
                List.of(), "3m", bd("100"), "FILLED", "2026-06-01 10:00:00.0", null, preyId);
    }

    private static ExecutorPosition closedPosition(long id, String symbol, String signalId,
            BigDecimal exitPrice, BigDecimal realizedR) {
        return closedPosition(id, symbol, signalId, exitPrice, realizedR, "operator");
    }

    /** A COMPLETE {@code outcome_log} TRADE row for {@code positionId}, carrying the FINAL
     *  quantity-weighted values {@link OutcomeBatchJob} would have written — the data source
     *  {@link OutcomeMemoryScanJob} must read from, never the position's own (un-weighted,
     *  possibly-stale) fields. */
    private static OutcomeLogRow completeTradeRow(long positionId, String symbol,
            BigDecimal realizedR, BigDecimal maeR, BigDecimal mfeR, Integer holdingDays) {
        return new OutcomeLogRow("TRADE", "log-" + positionId, positionId, symbol, null,
                true, null, null, holdingDays, mfeR, maeR, realizedR, null, null, null,
                null, null, null, null, null, null, null, true);
    }

    private static ExecutorPosition closedPosition(long id, String symbol, String signalId,
            BigDecimal exitPrice, BigDecimal realizedR, String sourceAgent) {
        return new ExecutorPosition(
                id, "depot-1", symbol, "BUY", bd("100"), bd("100"), bd("95"), bd("95"), 1, bd("5"),
                List.of(), signalId, sourceAgent, "2026-06-01 10:00:00.0", null, "CLOSED", null,
                bd("100"), bd("2.0"), 0, exitPrice, realizedR, "TAKE_PROFIT",
                "2026-06-10 10:00:00.0", null, null, null, null, null, 0, bd("93"), null, null,
                null, null, null);
    }

    // ------------------------------------------------------------------------------------------
    // (a) two same-symbol hunts -> two signals -> two positions -> two DISTINCT cells via prey_id
    // ------------------------------------------------------------------------------------------

    @Test
    void twoSameSymbolHunts_resolveToDistinctCellsViaPreyId() {
        ResearchMemoryLink link1 = link(1L, "prey-1", "ACME", "cell-1");
        ResearchMemoryLink link2 = link(2L, "prey-2", "ACME", "cell-2");
        backLinksWith(List.of(link1, link2));

        when(signals.findById("sig-1")).thenReturn(signal("sig-1", "ACME", "prey-1"));
        when(signals.findById("sig-2")).thenReturn(signal("sig-2", "ACME", "prey-2"));
        when(positions.findClosed()).thenReturn(List.of(
                closedPosition(10L, "ACME", "sig-1", bd("110"), bd("1.0"), "strigoi-spin"),
                closedPosition(11L, "ACME", "sig-2", bd("90"), bd("-1.0"), "strigoi-spin")));
        when(outcomeLog.findCompleteTradeByPositionId(10L)).thenReturn(
                completeTradeRow(10L, "ACME", bd("1.0"), null, null, 9));
        when(outcomeLog.findCompleteTradeByPositionId(11L)).thenReturn(
                completeTradeRow(11L, "ACME", bd("-1.0"), null, null, 9));
        when(memory.writeOutcomeCell(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(true);

        job.scanOnce();

        ArgumentCaptor<String> cellIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(memory, times(2)).writeOutcomeCell(cellIdCaptor.capture(), anyString(), anyString(),
                any(), any(), any(), any());
        assertThat(cellIdCaptor.getAllValues()).containsExactlyInAnyOrder("cell-1", "cell-2");
        verify(links).markOutcomeWritten(1L);
        verify(links).markOutcomeWritten(2L);
    }

    // ------------------------------------------------------------------------------------------
    // (b) HiveMem down on the completion scan -> retried by a LATER scan, driven by the boolean
    // ------------------------------------------------------------------------------------------

    @Test
    void hiveMemDegradeOnFirstScan_retriedOnLaterScan() {
        ResearchMemoryLink link = link(5L, "prey-5", "ACME", "cell-5");
        backLinksWith(List.of(link));

        when(signals.findById("sig-5")).thenReturn(signal("sig-5", "ACME", "prey-5"));
        ExecutorPosition position = closedPosition(20L, "ACME", "sig-5", bd("110"), bd("1.0"), "strigoi-spin");
        when(positions.findClosed()).thenReturn(List.of(position));
        when(outcomeLog.findCompleteTradeByPositionId(20L)).thenReturn(
                completeTradeRow(20L, "ACME", bd("1.0"), null, null, 9));

        when(memory.writeOutcomeCell(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(false);

        job.scanOnce();

        verify(memory, times(1)).writeOutcomeCell(eq("cell-5"), anyString(), anyString(), any(), any(), any(), any());
        verify(links, never()).markOutcomeWritten(anyLong());

        when(memory.writeOutcomeCell(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(true);

        job.scanOnce();

        verify(memory, times(2)).writeOutcomeCell(eq("cell-5"), anyString(), anyString(), any(), any(), any(), any());
        verify(links, times(1)).markOutcomeWritten(5L);
    }

    // ------------------------------------------------------------------------------------------
    // (c) outcome_written idempotency: a second scanOnce() on an already-written link is a no-op
    // ------------------------------------------------------------------------------------------

    @Test
    void alreadyWrittenLink_secondScanIsNoOp() {
        ResearchMemoryLink link = link(6L, "prey-6", "ACME", "cell-6");
        backLinksWith(List.of(link));

        when(signals.findById("sig-6")).thenReturn(signal("sig-6", "ACME", "prey-6"));
        when(positions.findClosed()).thenReturn(List.of(
                closedPosition(21L, "ACME", "sig-6", bd("110"), bd("1.0"), "strigoi-spin")));
        when(outcomeLog.findCompleteTradeByPositionId(21L)).thenReturn(
                completeTradeRow(21L, "ACME", bd("1.0"), null, null, 9));
        when(memory.writeOutcomeCell(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(true);

        job.scanOnce();
        verify(memory, times(1)).writeOutcomeCell(eq("cell-6"), anyString(), anyString(), any(), any(), any(), any());
        verify(links, times(1)).markOutcomeWritten(6L);

        // second run: the link no longer comes back from findUnwrittenPreyLinks (flipped above)
        job.scanOnce();
        verify(memory, times(1)).writeOutcomeCell(eq("cell-6"), anyString(), anyString(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------------------------------
    // (d) null sourceSignalId / null preyId / missing link -> no-op, no WARN-spam
    // ------------------------------------------------------------------------------------------

    @Test
    void nullSourceSignalId_isNoOpWithoutWarn() {
        backLinksWith(List.of(link(7L, "prey-7", "ACME", "cell-7")));
        when(positions.findClosed()).thenReturn(List.of(
                closedPosition(22L, "ACME", null, bd("110"), bd("1.0"), "operator")));

        job.scanOnce();

        verifyNoInteractions(memory);
        assertThat(appender.list).noneSatisfy(ev -> assertThat(ev.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void nullPreyIdOnSignal_isNoOpWithoutWarn() {
        backLinksWith(List.of(link(8L, "prey-8", "ACME", "cell-8")));
        when(signals.findById("sig-8")).thenReturn(signal("sig-8", "ACME", null)); // preyId null
        when(positions.findClosed()).thenReturn(List.of(
                closedPosition(23L, "ACME", "sig-8", bd("110"), bd("1.0"), "strigoi-spin")));

        job.scanOnce();

        verifyNoInteractions(memory);
        assertThat(appender.list).noneSatisfy(ev -> assertThat(ev.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void resolvedPreyWithNoResearchMemoryLink_isNoOpWithoutWarn() {
        backLinksWith(List.of()); // no link recorded for this prey at all
        when(signals.findById("sig-9")).thenReturn(signal("sig-9", "ACME", "prey-9"));
        when(positions.findClosed()).thenReturn(List.of(
                closedPosition(24L, "ACME", "sig-9", bd("110"), bd("1.0"), "strigoi-spin")));

        job.scanOnce();

        verifyNoInteractions(memory);
        assertThat(appender.list).noneSatisfy(ev -> assertThat(ev.getLevel()).isEqualTo(Level.WARN));
    }

    // ------------------------------------------------------------------------------------------
    // (e) injected-signal position (operator source, no matching prey) -> no-op
    // ------------------------------------------------------------------------------------------

    @Test
    void injectedOperatorPosition_isNoOp() {
        backLinksWith(List.of(link(9L, "prey-10", "ACME", "cell-10")));
        // Injected position has no source_signal_id at all — the only chain into prey_id.
        when(positions.findClosed()).thenReturn(List.of(
                closedPosition(25L, "ACME", null, bd("110"), bd("1.0"), "operator")));

        job.scanOnce();

        verifyNoInteractions(memory);
        verifyNoInteractions(signals);
    }

    // ------------------------------------------------------------------------------------------
    // (f) outcome-cell content: realizedR/maeR/mfeR/holdingDays come from the COMPLETE outcome_log
    // row (the final, quantity-weighted values) — NEVER from the position's own un-weighted fields.
    // ------------------------------------------------------------------------------------------

    @Test
    void closedTrade_writesFinalWeightedValuesFromCompleteOutcomeLogRow() {
        backLinksWith(List.of(link(12L, "prey-12", "ACME", "cell-12")));
        when(signals.findById("sig-12")).thenReturn(signal("sig-12", "ACME", "prey-12"));
        // position's own realizedR/holdingDays deliberately differ from the outcome_log row below,
        // to prove the cell content is sourced from the row, not the position.
        ExecutorPosition position = closedPosition(26L, "ACME", "sig-12", bd("110"), bd("1.4"), "strigoi-spin");
        when(positions.findClosed()).thenReturn(List.of(position));
        when(outcomeLog.findCompleteTradeByPositionId(26L)).thenReturn(
                completeTradeRow(26L, "ACME", bd("1.1"), bd("-0.3"), bd("2.2"), 11));
        when(memory.writeOutcomeCell(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(true);

        job.scanOnce();

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> maeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> mfeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Integer> holdingDaysCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(memory).writeOutcomeCell(eq("cell-12"), eq("ACME"), eq("SPINOFF"),
                realizedRCaptor.capture(), maeCaptor.capture(), mfeCaptor.capture(),
                holdingDaysCaptor.capture());

        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("1.1");
        assertThat(maeCaptor.getValue()).isEqualByComparingTo("-0.3");
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("2.2");
        assertThat(holdingDaysCaptor.getValue()).isEqualTo(11);
    }

    // ------------------------------------------------------------------------------------------
    // (h) M3 reentry gate: a closed position whose outcome_log TRADE row is NOT yet complete
    // (still within OutcomeBatchJob's 14-calendar-day re-entry window) must NOT get a cell —
    // the scan retries on a later run, and writes exactly once the row flips complete.
    // ------------------------------------------------------------------------------------------

    @Test
    void closedPositionOutcomeLogNotYetComplete_writesNoCellUntilReentryWindowElapses() {
        backLinksWith(List.of(link(15L, "prey-15", "ACME", "cell-15")));
        when(signals.findById("sig-15")).thenReturn(signal("sig-15", "ACME", "prey-15"));
        ExecutorPosition position = closedPosition(27L, "ACME", "sig-15", bd("110"), bd("1.0"), "strigoi-spin");
        when(positions.findClosed()).thenReturn(List.of(position));

        // still inside the re-entry window: OutcomeBatchJob hasn't finalized the TRADE row yet.
        when(outcomeLog.findCompleteTradeByPositionId(27L)).thenReturn(null);

        job.scanOnce();

        verifyNoInteractions(memory);
        verify(links, never()).markOutcomeWritten(anyLong());

        // re-entry window elapses: outcome_log row flips complete with the final weighted R.
        when(outcomeLog.findCompleteTradeByPositionId(27L)).thenReturn(
                completeTradeRow(27L, "ACME", bd("1.0"), null, null, 14));
        when(memory.writeOutcomeCell(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(true);

        job.scanOnce();

        verify(memory, times(1)).writeOutcomeCell(eq("cell-15"), anyString(), anyString(), any(), any(), any(), any());
        verify(links, times(1)).markOutcomeWritten(15L);
    }

    // ------------------------------------------------------------------------------------------
    // (g) never-traded link older than max(horizon)+reentry window -> excluded from later scans
    // ------------------------------------------------------------------------------------------

    @Test
    void neverTradedStaleLink_excludedFromLaterScans() {
        Instant old = Instant.now().minus(200, ChronoUnit.DAYS);
        ResearchMemoryLink staleLink = link(13L, "prey-13", "ACME", "cell-13", old);
        backLinksWith(List.of(staleLink));
        when(positions.findClosed()).thenReturn(List.of()); // never traded

        job.scanOnce();

        verify(links, times(1)).markExcluded(13L);
        verifyNoInteractions(memory);

        // a later scan no longer sees/returns the excluded link
        assertThat(links.findUnwrittenPreyLinks(500)).isEmpty();
    }

    @Test
    void freshUnwrittenLink_notExcludedYet() {
        ResearchMemoryLink freshLink = link(14L, "prey-14", "ACME", "cell-14", Instant.now());
        backLinksWith(List.of(freshLink));
        when(positions.findClosed()).thenReturn(List.of()); // never (yet) traded

        job.scanOnce();

        verify(links, never()).markExcluded(anyLong());
        assertThat(links.findUnwrittenPreyLinks(500)).hasSize(1);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
