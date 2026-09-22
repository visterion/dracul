package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.AnchorCandidate;
import de.visterion.dracul.executor.AnchorShadowRow;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit test for {@link AnchorReconstructionStep}, mirroring
 * {@code AnchorReconstructorTest}'s and {@code OutcomeBatchJobTest}'s style. The Clock is fixed
 * at 2026-09-22T22:30:00Z so "today" and every age-in-days computation are pinned.
 */
class AnchorReconstructionStepTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T22:30:00Z"), ZoneOffset.UTC);

    private final ExecutorSignalRepository signals = mock(ExecutorSignalRepository.class);
    private final AgoraMarketData marketData = mock(AgoraMarketData.class);

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private AnchorReconstructionStep step(boolean enabled, int maxPerRun, int shadowSample) {
        return new AnchorReconstructionStep(signals, marketData, enabled, maxPerRun, shadowSample, CLOCK);
    }

    /** Weekday bars from start (inclusive) for n trading days. Alternating closes 100/110 with
     *  narrow ranges: every TR is 12 while every H-L is 4, so an implementation using H-L
     *  yields 4.0000 instead of 12.0000. Copied from AnchorReconstructorTest. */
    private static List<OhlcBar> alternating(LocalDate start, int n) {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate d = start;
        int i = 0;
        while (out.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                boolean odd = i % 2 == 1;
                BigDecimal c = odd ? bd("110") : bd("100");
                BigDecimal h = odd ? bd("112") : bd("102");
                BigDecimal l = odd ? bd("108") : bd("98");
                out.add(new OhlcBar(d, c, h, l, c, 1000));
                i++;
            }
            d = d.plusDays(1);
        }
        return out;
    }

    /** 70 weekday bars ending exactly on 2026-09-22 (the fixed clock's "today"). */
    private static final List<OhlcBar> FULL_BARS = alternating(LocalDate.parse("2026-06-17"), 70);

    /** Same series truncated to end on 2026-08-24 — three weeks before a 2026-09-15 emission, so
     *  reconstruction against it is STALE rather than a genuine anchor. */
    private static final List<OhlcBar> STALE_BARS = FULL_BARS.stream()
            .filter(b -> !b.date().isAfter(LocalDate.parse("2026-08-24"))).toList();

    // --- 1: happy path ------------------------------------------------------------------------

    @Test
    void reconstructsAndWritesAnchor() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-1", "TESTCO", Instant.parse("2026-09-15T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO"), anyInt())).thenReturn(FULL_BARS);
        when(signals.writeReconstructedAnchor(eq("sig-1"), eq(LocalDate.parse("2026-09-14")), eq(bd("12.0000"))))
                .thenReturn(1);

        AnchorReconstructionStep.Summary summary = step(true, 100, 0).run();

        verify(signals).writeReconstructedAnchor("sig-1", LocalDate.parse("2026-09-14"), bd("12.0000"));
        assertThat(summary.reconstructed()).isEqualTo(1);
        assertThat(summary.candidates()).isEqualTo(1);
    }

    // --- 2: fetch dedup ------------------------------------------------------------------------

    @Test
    void oneFetchPerSymbol() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-a", "TESTCO", Instant.parse("2026-09-15T04:01:00Z")),
                new AnchorCandidate("sig-b", "TESTCO", Instant.parse("2026-09-14T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO"), anyInt())).thenReturn(FULL_BARS);
        when(signals.writeReconstructedAnchor(anyString(), any(), any())).thenReturn(1);

        step(true, 100, 0).run();

        verify(marketData, times(1)).dailyOhlcHistory(eq("TESTCO"), anyInt());
        verify(signals).writeReconstructedAnchor(eq("sig-a"), any(), any());
        verify(signals).writeReconstructedAnchor(eq("sig-b"), any(), any());
    }

    // --- 3: lookback sizing --------------------------------------------------------------------

    @Test
    void lookbackCoversOldestEmissionPlus60() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-1", "TESTCO", Instant.parse("2026-07-20T21:00:00Z"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO"), eq(124))).thenReturn(FULL_BARS);

        step(true, 100, 0).run();

        verify(marketData).dailyOhlcHistory("TESTCO", 124);
    }

    // --- 4: transient fetch failure ------------------------------------------------------------

    @Test
    void marketDataExceptionDefers() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-1", "TESTCO", Instant.parse("2026-09-15T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));

        AnchorReconstructionStep.Summary summary = step(true, 100, 0).run();

        verify(signals, never()).writeReconstructedAnchor(any(), any(), any());
        verify(signals, never()).markUnreconstructable(any());
        assertThat(summary.deferred()).isEqualTo(1);
    }

    // --- 5: race on write ------------------------------------------------------------------------

    @Test
    void raceCountsZeroRowUpdate() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-1", "TESTCO", Instant.parse("2026-09-15T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO"), anyInt())).thenReturn(FULL_BARS);
        when(signals.writeReconstructedAnchor(any(), any(), any())).thenReturn(0);

        AnchorReconstructionStep.Summary summary = step(true, 100, 0).run();

        assertThat(summary.raced()).isEqualTo(1);
        assertThat(summary.reconstructed()).isEqualTo(0);
    }

    // --- 6: permanent failure ------------------------------------------------------------------

    @Test
    void permanentFailureMarks() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-1", "TESTCO.L", Instant.parse("2026-09-15T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO.L"), anyInt())).thenReturn(FULL_BARS);
        when(signals.markUnreconstructable("sig-1")).thenReturn(1);

        AnchorReconstructionStep.Summary summary = step(true, 100, 0).run();

        verify(signals).markUnreconstructable("sig-1");
        assertThat(summary.unreconstructable()).isEqualTo(1);
        assertThat(summary.reasons()).containsEntry("unsupported venue", 1);
    }

    @Test
    void permanentFailureMarkRaceCountsAsRaced() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-1", "TESTCO.L", Instant.parse("2026-09-15T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO.L"), anyInt())).thenReturn(FULL_BARS);
        when(signals.markUnreconstructable("sig-1")).thenReturn(0);

        AnchorReconstructionStep.Summary summary = step(true, 100, 0).run();

        assertThat(summary.raced()).isEqualTo(1);
        assertThat(summary.unreconstructable()).isEqualTo(0);
        // the counters must add up: exactly one candidate, and it landed in raced, not lost
        assertThat(summary.reconstructed() + summary.unreconstructable() + summary.deferred()
                + summary.raced()).isEqualTo(summary.candidates());
    }

    // --- 7: outage guard -----------------------------------------------------------------------

    @Test
    void outageGuardDefersEverything() {
        // A second candidate whose OWN reconstruction would otherwise be a PERMANENT failure
        // (unsupported venue) is what makes this test non-vacuous: without the "|| outage"
        // short-circuit in the candidate loop, TESTCO.L would reach reconstruct() and be marked
        // unreconstructable regardless of the outage, since UNSUPPORTED_VENUE never even looks at
        // the (empty) bars. With the guard, EVERY candidate defers during an outage.
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-1", "TESTCO", Instant.parse("2026-09-15T04:01:00Z")),
                new AnchorCandidate("sig-2", "TESTCO.L", Instant.parse("2026-09-15T04:01:00Z"))));
        when(signals.findShadowSample(eq(5), any())).thenReturn(List.of(
                new AnchorShadowRow("sig-shadow", "SHADCO", Instant.parse("2026-09-18T21:00:00Z"),
                        LocalDate.parse("2026-09-17"), bd("5.0000"))));
        when(marketData.dailyOhlcHistory(anyString(), anyInt())).thenReturn(List.of());

        AnchorReconstructionStep.Summary summary = step(true, 100, 5).run();

        verify(signals, never()).writeReconstructedAnchor(any(), any(), any());
        verify(signals, never()).markUnreconstructable(any());
        assertThat(summary.deferred()).isEqualTo(2);
    }

    // --- 8: empty-symbol escalation, healthy peer among candidates -----------------------------

    @Test
    void emptySymbolWithHealthyPeersOlderThan30DaysIsPermanent() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-empty", "EMPTYCO", Instant.parse("2026-08-01T21:00:00Z")),
                new AnchorCandidate("sig-testco", "TESTCO", Instant.parse("2026-09-15T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("EMPTYCO"), anyInt())).thenReturn(List.of());
        when(marketData.dailyOhlcHistory(eq("TESTCO"), anyInt())).thenReturn(FULL_BARS);
        when(signals.markUnreconstructable("sig-empty")).thenReturn(1);
        when(signals.writeReconstructedAnchor(eq("sig-testco"), any(), any())).thenReturn(1);

        AnchorReconstructionStep.Summary summary = step(true, 100, 0).run();

        verify(signals).markUnreconstructable("sig-empty");
        assertThat(summary.reasons()).containsEntry(AnchorReconstructionStep.NO_HISTORY, 1);
    }

    @Test
    void emptySymbolWithHealthyPeersWithin30DaysStaysDeferred() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-empty", "EMPTYCO", Instant.parse("2026-09-01T21:00:00Z")),
                new AnchorCandidate("sig-testco", "TESTCO", Instant.parse("2026-09-15T04:01:00Z"))));
        when(marketData.dailyOhlcHistory(eq("EMPTYCO"), anyInt())).thenReturn(List.of());
        when(marketData.dailyOhlcHistory(eq("TESTCO"), anyInt())).thenReturn(FULL_BARS);
        when(signals.writeReconstructedAnchor(eq("sig-testco"), any(), any())).thenReturn(1);

        AnchorReconstructionStep.Summary summary = step(true, 100, 0).run();

        verify(signals, never()).markUnreconstructable("sig-empty");
        assertThat(summary.reasons()).containsEntry(de.visterion.dracul.outcome.AnchorReconstructor.NO_DATA, 1);
    }

    // --- 9: shadow fetches count as peers --------------------------------------------------------

    @Test
    void singleSymbolRunUsesShadowFetchesAsPeers() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-empty", "EMPTYCO", Instant.parse("2026-08-01T21:00:00Z"))));
        when(signals.findShadowSample(eq(5), any())).thenReturn(List.of(
                new AnchorShadowRow("sig-shad", "SHADCO", Instant.parse("2026-09-18T21:00:00Z"),
                        LocalDate.parse("2026-09-17"), bd("5.0000"))));
        when(marketData.dailyOhlcHistory(eq("EMPTYCO"), anyInt())).thenReturn(List.of());
        when(marketData.dailyOhlcHistory(eq("SHADCO"), anyInt())).thenReturn(FULL_BARS);
        when(signals.markUnreconstructable("sig-empty")).thenReturn(1);

        AnchorReconstructionStep.Summary summary = step(true, 100, 5).run();

        verify(signals).markUnreconstructable("sig-empty");
        assertThat(summary.reasons()).containsEntry(AnchorReconstructionStep.NO_HISTORY, 1);
    }

    // --- 10: an exception fetch is never a peer ---------------------------------------------------

    @Test
    void exceptionSymbolIsNotAPeer() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of(
                new AnchorCandidate("sig-empty", "EMPTYCO", Instant.parse("2026-08-01T21:00:00Z"))));
        when(signals.findShadowSample(eq(5), any())).thenReturn(List.of(
                new AnchorShadowRow("sig-shad", "SHADCO", Instant.parse("2026-09-18T21:00:00Z"),
                        LocalDate.parse("2026-09-17"), bd("5.0000"))));
        when(marketData.dailyOhlcHistory(eq("EMPTYCO"), anyInt())).thenReturn(List.of());
        when(marketData.dailyOhlcHistory(eq("SHADCO"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));

        AnchorReconstructionStep.Summary summary = step(true, 100, 5).run();

        verify(signals, never()).markUnreconstructable(any());
        verify(signals, never()).writeReconstructedAnchor(any(), any(), any());
        assertThat(summary.deferred()).isEqualTo(1);
    }

    // --- 11: disabled ----------------------------------------------------------------------------

    @Test
    void disabledDoesNothing() {
        AnchorReconstructionStep.Summary summary = step(false, 100, 10).run();

        assertThat(summary).isEqualTo(AnchorReconstructionStep.Summary.disabled());
        verifyNoInteractions(signals, marketData);
    }

    // --- 12: cap passed to finder -----------------------------------------------------------------

    @Test
    void capIsPassedToFinder() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of());

        step(true, 100, 0).run();

        verify(signals).findAnchorCandidates(100);
    }

    // --- 13: shadow match, no writes ---------------------------------------------------------------

    @Test
    void shadowDateAndAtrMatchNeverWrites() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of());
        when(signals.findShadowSample(eq(5), any())).thenReturn(List.of(
                new AnchorShadowRow("sig-shad", "TESTCO", Instant.parse("2026-09-15T04:01:00Z"),
                        LocalDate.parse("2026-09-14"), bd("12.0000"))));
        when(marketData.dailyOhlcHistory(eq("TESTCO"), anyInt())).thenReturn(FULL_BARS);

        AnchorReconstructionStep.Summary summary = step(true, 100, 5).run();

        assertThat(summary.shadowN()).isEqualTo(1);
        assertThat(summary.shadowDateMatch()).isEqualTo(1);
        assertThat(summary.shadowAtrMatch()).isEqualTo(1);
        assertThat(summary.shadowMismatch()).isEqualTo(0);
        assertThat(summary.shadowSkipped()).isEqualTo(0);
        verify(signals, never()).writeReconstructedAnchor(any(), any(), any());
        verify(signals, never()).markUnreconstructable(any());
    }

    // --- 14: shadow ATR tolerance boundaries (direct unit test of the pure helper) ----------------

    @Test
    void shadowAtrToleranceBoundaries() {
        // 1.7% off a stored 12.0000 -> within the 2% relative tolerance -> match
        assertThat(AnchorReconstructionStep.atrWithinTolerance(bd("12.2000"), bd("12.0000"))).isTrue();
        // 2.5% off -> outside the 2% relative tolerance -> mismatch
        assertThat(AnchorReconstructionStep.atrWithinTolerance(bd("12.3000"), bd("12.0000"))).isFalse();
        // 5% relative but only 0.0001 absolute off a tiny stored value -> the absolute floor covers it
        assertThat(AnchorReconstructionStep.atrWithinTolerance(bd("0.0021"), bd("0.0020"))).isTrue();
    }

    // --- 15: shadow failure classes ------------------------------------------------------------

    @Test
    void shadowFailureClasses() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of());
        when(signals.findShadowSample(eq(5), any())).thenReturn(List.of(
                // series ends three weeks before emission -> STALE -> expected divergence
                new AnchorShadowRow("sig-stale", "STALECO", Instant.parse("2026-09-15T04:01:00Z"),
                        LocalDate.parse("2026-08-01"), bd("5.0000")),
                // unsupported venue -> failure, but NOT the expected STALE class -> mismatch
                new AnchorShadowRow("sig-venue", "TESTCO.L", Instant.parse("2026-09-15T04:01:00Z"),
                        LocalDate.parse("2026-09-14"), bd("12.0000"))));
        when(marketData.dailyOhlcHistory(eq("STALECO"), anyInt())).thenReturn(STALE_BARS);
        when(marketData.dailyOhlcHistory(eq("TESTCO.L"), anyInt())).thenReturn(FULL_BARS);

        AnchorReconstructionStep.Summary summary = step(true, 100, 5).run();

        assertThat(summary.shadowN()).isEqualTo(2);
        assertThat(summary.shadowExpectedDivergence()).isEqualTo(1);
        assertThat(summary.shadowDateMatch()).isEqualTo(0);
        assertThat(summary.shadowAtrMatch()).isEqualTo(0);
        // the STALE row is an expected divergence, not a mismatch; the unsupported-venue row is
        // neither STALE nor a match -> exactly one mismatch
        assertThat(summary.shadowMismatch()).isEqualTo(1);
        assertThat(summary.shadowSkipped()).isEqualTo(0);
    }

    // --- shadow row whose fetch itself failed: skipped, never compared, never written/marked ------

    @Test
    void shadowRowWithFailedFetchIsSkipped() {
        when(signals.findAnchorCandidates(100)).thenReturn(List.of());
        when(signals.findShadowSample(eq(5), any())).thenReturn(List.of(
                new AnchorShadowRow("sig-shad-down", "DOWNCO", Instant.parse("2026-09-15T04:01:00Z"),
                        LocalDate.parse("2026-09-14"), bd("12.0000"))));
        when(marketData.dailyOhlcHistory(eq("DOWNCO"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));

        AnchorReconstructionStep.Summary summary = step(true, 100, 5).run();

        assertThat(summary.shadowN()).isEqualTo(1);
        assertThat(summary.shadowSkipped()).isEqualTo(1);
        assertThat(summary.shadowDateMatch()).isEqualTo(0);
        assertThat(summary.shadowAtrMatch()).isEqualTo(0);
        assertThat(summary.shadowMismatch()).isEqualTo(0);
        assertThat(summary.shadowExpectedDivergence()).isEqualTo(0);
        verify(signals, never()).writeReconstructedAnchor(any(), any(), any());
        verify(signals, never()).markUnreconstructable(any());
    }

    // --- 16: shadowNeverWrites is covered by the assertions in 13-15 above -------------------------
}
