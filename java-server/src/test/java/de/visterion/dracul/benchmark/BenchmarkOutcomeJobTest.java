package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static de.visterion.dracul.benchmark.BenchmarkFixtures.series;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BenchmarkOutcomeJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-30T00:15:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = d(2026, 6, 30);

    private final BenchmarkOutcomeRepository repo = mock(BenchmarkOutcomeRepository.class);
    private final BenchmarkBars bars = mock(BenchmarkBars.class);

    private BenchmarkOutcomeJob job(int maxSymbols) {
        return new BenchmarkOutcomeJob(repo, bars, maxSymbols, CLOCK);
    }

    private static PreyRow prey(String symbol, String iso) {
        return new PreyRow("strigoi-syna", symbol, Instant.parse(iso));
    }

    @BeforeEach
    void defaults() {
        when(repo.findCandidates()).thenReturn(List.of());
        when(repo.completedEventKeys()).thenReturn(new HashSet<>());
        when(repo.insert(any())).thenReturn(true);
        when(repo.noDataNights(anyString())).thenReturn(0);
        when(bars.fetch(eq("EUNL.DE"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 29), "100"));
        when(bars.fetch(eq("USDEUR=X"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 29), "0.9"));
        when(bars.fetch(eq("HKDEUR=X"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 29), "0.11"));
    }

    @Test
    void writesBothHorizonsForACompleteUsdSignal() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNJ", "2026-03-01T22:00:00Z")));
        when(bars.fetch(eq("SYNJ"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(repo, times(2)).insert(any());
        assertThat(s.eventsScanned()).isEqualTo(1);
        assertThat(s.rowsWritten()).isEqualTo(2);
        assertThat(s.pending()).isZero();
        verify(repo).clearNoData("SYNJ");
    }

    /** Spec §6 test 1 (second half): an unknown suffix is counted, never fetched, never stored. */
    @Test
    void unknownCurrencyIsCountedAndNeverFetched() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNX.ZZ", "2026-03-01T22:00:00Z")));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        assertThat(s.unknownCurrency()).isEqualTo(1);
        verify(bars, never()).fetch(eq("SYNX.ZZ"), anyInt());
        verify(repo, never()).insert(any());
    }

    @Test
    void theSymbolCapFetchesTheOldestEventsSymbolFirst() {
        when(repo.findPrey()).thenReturn(List.of(
                prey("SYNK", "2026-03-10T22:00:00Z"), prey("SYNJ", "2026-03-01T22:00:00Z")));
        when(bars.fetch(eq("SYNJ"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));

        BenchmarkOutcomeJob.RunSummary s = job(1).runOnce();

        verify(bars, never()).fetch(eq("SYNK"), anyInt());
        assertThat(s.pending()).isEqualTo(1);
        assertThat(s.rowsWritten()).isEqualTo(2);
    }

    @Test
    void emptyBarsCountANoDataNightAndAGivenUpSymbolIsNeverFetched() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNJ", "2026-03-01T22:00:00Z")));
        when(bars.fetch(eq("SYNJ"), anyInt())).thenReturn(PriceSeries.empty());

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();
        verify(repo).recordNoData("SYNJ", TODAY);
        assertThat(s.noData()).isEqualTo(1);
        verify(repo, never()).insert(any());

        when(repo.noDataNights("SYNJ")).thenReturn(3);
        BenchmarkOutcomeJob.RunSummary again = job(150).runOnce();
        verify(bars, times(1)).fetch(eq("SYNJ"), anyInt());
        assertThat(again.noData()).isEqualTo(1);
    }

    @Test
    void aRequestScopedAgoraErrorIsNoDataButASourceErrorIsSourceFailed() {
        when(repo.findPrey()).thenReturn(List.of(
                prey("SYNJ", "2026-03-01T22:00:00Z"), prey("SYNK", "2026-03-02T22:00:00Z")));
        when(bars.fetch(eq("SYNJ"), anyInt())).thenThrow(new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "unknown symbol",
                new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST, "unknown symbol", null)));
        when(bars.fetch(eq("SYNK"), anyInt())).thenThrow(new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "down", new AgoraUnavailableException("down")));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(repo).recordNoData("SYNJ", TODAY);
        verify(repo, never()).recordNoData(eq("SYNK"), any());
        assertThat(s.noData()).isEqualTo(1);
        assertThat(s.sourceFailed()).isEqualTo(1);
        assertThat(s.pending()).isEqualTo(1);
    }

    @Test
    void threeConsecutiveSourceFailuresDeferTheRestOfTheNight() {
        when(repo.findPrey()).thenReturn(List.of(
                prey("SYNA", "2026-03-01T22:00:00Z"), prey("SYNB", "2026-03-02T22:00:00Z"),
                prey("SYNC", "2026-03-03T22:00:00Z"), prey("SYND", "2026-03-04T22:00:00Z")));
        MarketDataException down = new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down",
                new AgoraUnavailableException("down"));
        when(bars.fetch(eq("SYNA"), anyInt())).thenThrow(down);
        when(bars.fetch(eq("SYNB"), anyInt())).thenThrow(down);
        when(bars.fetch(eq("SYNC"), anyInt())).thenThrow(down);

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, never()).fetch(eq("SYND"), anyInt());
        assertThat(s.sourceFailed()).isEqualTo(3);
        assertThat(s.pending()).isEqualTo(4);
    }

    @Test
    void theFxPairIsFetchedOncePerRun() {
        when(repo.findPrey()).thenReturn(List.of(
                prey("SYN1.HK", "2026-03-01T22:00:00Z"), prey("SYN2.HK", "2026-03-02T22:00:00Z")));
        when(bars.fetch(eq("SYN1.HK"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));
        when(bars.fetch(eq("SYN2.HK"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, times(1)).fetch(eq("HKDEUR=X"), anyInt());
        verify(bars, times(1)).fetch(eq("EUNL.DE"), anyInt());
        assertThat(s.rowsWritten()).isEqualTo(4);
    }

    @Test
    void anUnavailableBenchmarkMeasuresNothing() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNJ", "2026-03-01T22:00:00Z")));
        when(bars.fetch(eq("EUNL.DE"), anyInt())).thenThrow(new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "down", new AgoraUnavailableException("down")));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, never()).fetch(eq("SYNJ"), anyInt());
        verify(repo, never()).insert(any());
        assertThat(s.sourceFailed()).isEqualTo(1);
    }

    /** Controller ruling 3: an empty EUNL series (no exception, just no bars) must not build,
     *  settle or measure anything — TradingCalendar.of(List.of()) would otherwise extrapolate
     *  weekdays and silently fabricate a calendar for a dead benchmark feed. */
    @Test
    void anEmptyBenchmarkSeriesMeasuresNothing() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNJ", "2026-03-01T22:00:00Z")));
        when(bars.fetch(eq("EUNL.DE"), anyInt())).thenReturn(PriceSeries.empty());

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, never()).fetch(eq("SYNJ"), anyInt());
        verify(repo, never()).insert(any());
        assertThat(s.sourceFailed()).isEqualTo(1);
        assertThat(s.pending()).isZero();
    }

    @Test
    void anUnsettledRejectedCandidateWaitsWithoutFetching() {
        when(repo.findPrey()).thenReturn(List.of());
        when(repo.findCandidates()).thenReturn(List.of(new CandidateRow("strigoi-merger", "SYNR",
                Instant.parse("2026-06-27T05:00:00Z"), d(2026, 6, 27), null, null)));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, never()).fetch(eq("SYNR"), anyInt());
        assertThat(s.pending()).isEqualTo(1);
    }

    @Test
    void alreadyCompletedEventsAreSkipped() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNJ", "2026-03-01T22:00:00Z")));
        HashSet<String> done = new HashSet<>();
        for (int h : OutcomeCalculator.HORIZONS) {
            done.add(BenchmarkOutcomeRepository.eventKey(EventKind.SIGNAL, "strigoi-syna", "SYNJ", d(2026, 3, 1), h));
        }
        when(repo.completedEventKeys()).thenReturn(done);

        job(150).runOnce();

        verify(bars, never()).fetch(eq("SYNJ"), anyInt());
    }

    @Test
    void lookbackBarsIsClampedTo260And1825() {
        assertThat(BenchmarkBars.lookbackBars(TODAY.minusDays(10), TODAY)).isEqualTo(260);
        assertThat(BenchmarkBars.lookbackBars(TODAY.minusDays(700), TODAY)).isEqualTo(530);
        assertThat(BenchmarkBars.lookbackBars(TODAY.minusYears(20), TODAY)).isEqualTo(1825);
    }
}
