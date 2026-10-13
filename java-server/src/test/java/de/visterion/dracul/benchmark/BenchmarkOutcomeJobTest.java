package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static de.visterion.dracul.benchmark.BenchmarkFixtures.series;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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

    private final Logger jobLog = (Logger) LoggerFactory.getLogger(BenchmarkOutcomeJob.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detach() {
        jobLog.detachAppender(appender);
    }

    private static MarketDataException requestError(String symbol) {
        return new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "unknown symbol " + symbol,
                new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST, "unknown symbol", null));
    }

    @BeforeEach
    void defaults() {
        appender.start();
        jobLog.addAppender(appender);
        when(repo.findCandidates()).thenReturn(List.of());
        when(repo.completedEventKeys()).thenReturn(new HashSet<>());
        when(repo.insert(any())).thenReturn(true);
        when(repo.noDataStates()).thenReturn(Map.of());
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

        when(repo.noDataStates()).thenReturn(Map.of("SYNJ", new BenchmarkOutcomeRepository.NoDataState(3, TODAY)));
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

    /** Final review I1b: the event identity differs (another event_start_date) but the table's
     *  unique-key tuple (kind, agent, symbol, entry_ref_date, horizon) already has a row — the
     *  event is done: no fetch, no insert, no conflict WARN. */
    @Test
    void anEventWhoseUniqueKeyTupleExistsIsSkippedWithoutFetchInsertOrWarn() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNJ", "2026-03-01T22:00:00Z")));
        HashSet<String> done = new HashSet<>();
        for (int h : OutcomeCalculator.HORIZONS) {
            // Written earlier by another event (event_start 2026-02-20) with the same entry_ref_date.
            done.add(BenchmarkOutcomeRepository.eventKey(EventKind.SIGNAL, "strigoi-syna", "SYNJ", d(2026, 2, 20), h));
            done.add(BenchmarkOutcomeRepository.refKey(EventKind.SIGNAL, "strigoi-syna", "SYNJ", d(2026, 3, 1), h));
        }
        when(repo.completedEventKeys()).thenReturn(done);
        when(bars.fetch(eq("SYNJ"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, never()).fetch(eq("SYNJ"), anyInt());
        verify(repo, never()).insert(any());
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        assertThat(s.rowsWritten()).isZero();
        assertThat(s.pending()).isZero();
    }

    /** Ten dead symbols (REQUEST errors), oldest first, then one live symbol. */
    private List<PreyRow> tenDeadThenOneLive() {
        List<PreyRow> prey = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String sym = "SYNQ" + (char) ('A' + i);
            prey.add(prey(sym, "2026-03-%02dT22:00:00Z".formatted(i + 1)));
            when(bars.fetch(eq(sym), anyInt())).thenThrow(requestError(sym));
        }
        prey.add(prey("SYNL", "2026-03-20T22:00:00Z"));
        when(bars.fetch(eq("SYNL"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));
        return prey;
    }

    /** Revised I4 test (a): ten dead symbols at the head of the queue, EUNL.DE probe healthy → the
     *  streak is real per-symbol no-data: booked, and the night continues to the live symbol. */
    @Test
    void tenRequestErrorsWithAHealthyProbeAreBookedAndTheNightContinues() {
        List<PreyRow> prey = tenDeadThenOneLive();
        when(repo.findPrey()).thenReturn(prey);

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(repo, times(10)).recordNoData(anyString(), eq(TODAY));
        verify(bars).fetch("EUNL.DE", BenchmarkOutcomeJob.PROBE_BARS);
        verify(bars).fetch(eq("SYNL"), anyInt());
        assertThat(s.rowsWritten()).isEqualTo(2);
        assertThat(s.noData()).isEqualTo(10);
        assertThat(s.sourceFailed()).isZero();
    }

    /** Revised I4 test (b): the next night the dead symbols have no-data rows, so they are fetched
     *  after the live one — they cannot block it — and still progress toward give-up. */
    @Test
    void onTheNextNightDeadSymbolsQueueBehindLiveOnesAndKeepProgressing() {
        List<PreyRow> prey = tenDeadThenOneLive();
        when(repo.findPrey()).thenReturn(prey);
        Map<String, BenchmarkOutcomeRepository.NoDataState> states = new java.util.HashMap<>();
        for (int i = 0; i < 10; i++) {
            states.put("SYNQ" + (char) ('A' + i), new BenchmarkOutcomeRepository.NoDataState(1, TODAY.minusDays(1)));
        }
        when(repo.noDataStates()).thenReturn(states);

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        InOrder order = inOrder(bars);
        order.verify(bars).fetch(eq("SYNL"), anyInt());
        order.verify(bars).fetch(eq("SYNQA"), anyInt());
        verify(repo, times(10)).recordNoData(anyString(), eq(TODAY));
        assertThat(s.rowsWritten()).isEqualTo(2);
        assertThat(s.noData()).isEqualTo(10);
    }

    /** Revised I4 test (c): ten request-scoped errors in a row and the EUNL.DE probe fails → source
     *  trouble: the night stops, the streak counts as source-failed, no no-data night is booked. */
    @Test
    void tenRequestErrorsWithAFailedProbeStopTheNightWithoutBookingNoData() {
        List<PreyRow> prey = tenDeadThenOneLive();
        when(repo.findPrey()).thenReturn(prey);
        when(bars.fetch(eq("EUNL.DE"), anyInt()))
                .thenReturn(series(d(2026, 1, 5), d(2026, 6, 29), "100"))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down",
                        new AgoraUnavailableException("down")));

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, never()).fetch(eq("SYNL"), anyInt());
        verify(repo, never()).recordNoData(anyString(), any());
        assertThat(s.sourceFailed()).isEqualTo(10);
        assertThat(s.noData()).isZero();
        assertThat(s.pending()).isEqualTo(11);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("10 request-scoped errors in a row")
                && e.getFormattedMessage().contains("probe failed"));
    }

    /** Revised I4 test (c'): an EMPTY probe answer counts as a failed probe. */
    @Test
    void anEmptyProbeAnswerAlsoStopsTheNight() {
        List<PreyRow> prey = tenDeadThenOneLive();
        when(repo.findPrey()).thenReturn(prey);
        when(bars.fetch(eq("EUNL.DE"), anyInt()))
                .thenReturn(series(d(2026, 1, 5), d(2026, 6, 29), "100"))
                .thenReturn(PriceSeries.empty());

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(bars, never()).fetch(eq("SYNL"), anyInt());
        verify(repo, never()).recordNoData(anyString(), any());
        assertThat(s.sourceFailed()).isEqualTo(10);
    }

    /** Revised I4 test (d): a retry-due symbol (older event) is fetched after a fresh one; with a
     *  cap of one symbol only the fresh one is fetched. */
    @Test
    void retryDueSymbolsAreOrderedAfterFreshOnes() {
        when(repo.findPrey()).thenReturn(List.of(
                prey("SYNR", "2026-03-01T22:00:00Z"), prey("SYNF", "2026-03-10T22:00:00Z")));
        when(repo.noDataStates()).thenReturn(Map.of("SYNR",
                new BenchmarkOutcomeRepository.NoDataState(3, TODAY.minusDays(28))));
        when(bars.fetch(eq("SYNR"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));
        when(bars.fetch(eq("SYNF"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));

        job(150).runOnce();
        InOrder order = inOrder(bars);
        order.verify(bars).fetch(eq("SYNF"), anyInt());
        order.verify(bars).fetch(eq("SYNR"), anyInt());

        BenchmarkOutcomeJob.RunSummary capped = job(1).runOnce();
        verify(bars, times(2)).fetch(eq("SYNF"), anyInt());
        verify(bars, times(1)).fetch(eq("SYNR"), anyInt());
        assertThat(capped.pending()).isEqualTo(1);
    }

    /** Final review I4: a successful fetch confirms the streak as real no-data and resets it. */
    @Test
    void aSuccessfulFetchResetsTheRequestErrorStreak() {
        List<PreyRow> prey = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String sym = "SYNQ" + (char) ('A' + i);
            prey.add(prey(sym, "2026-03-%02dT22:00:00Z".formatted(i + 1)));
            if (i != 9) when(bars.fetch(eq(sym), anyInt())).thenThrow(requestError(sym));
        }
        // The 10th symbol answers — the streak is 9, then 1: never 10 in a row.
        when(bars.fetch(eq("SYNQJ"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));
        when(repo.findPrey()).thenReturn(prey);

        BenchmarkOutcomeJob.RunSummary s = job(150).runOnce();

        verify(repo, times(10)).recordNoData(anyString(), eq(TODAY));
        verify(repo, never()).recordNoData(eq("SYNQJ"), any());
        verify(bars, never()).fetch("EUNL.DE", BenchmarkOutcomeJob.PROBE_BARS);
        assertThat(s.noData()).isEqualTo(10);
        assertThat(s.sourceFailed()).isZero();
        assertThat(s.rowsWritten()).isEqualTo(2);
    }

    /** Final review I4: a given-up symbol is retried once 28 days after its last no-data night. */
    @Test
    void aGivenUpSymbolIsRetriedTwentyEightDaysAfterItsLastNoDataNight() {
        when(repo.findPrey()).thenReturn(List.of(prey("SYNJ", "2026-03-01T22:00:00Z")));
        when(bars.fetch(eq("SYNJ"), anyInt())).thenReturn(series(d(2026, 1, 5), d(2026, 6, 26), "10"));

        when(repo.noDataStates()).thenReturn(Map.of("SYNJ",
                new BenchmarkOutcomeRepository.NoDataState(3, TODAY.minusDays(27))));
        BenchmarkOutcomeJob.RunSummary waiting = job(150).runOnce();
        verify(bars, never()).fetch(eq("SYNJ"), anyInt());
        assertThat(waiting.noData()).isEqualTo(1);

        when(repo.noDataStates()).thenReturn(Map.of("SYNJ",
                new BenchmarkOutcomeRepository.NoDataState(3, TODAY.minusDays(28))));
        BenchmarkOutcomeJob.RunSummary retried = job(150).runOnce();
        verify(bars, times(1)).fetch(eq("SYNJ"), anyInt());
        verify(repo).clearNoData("SYNJ");
        assertThat(retried.rowsWritten()).isEqualTo(2);
        assertThat(retried.noData()).isZero();
    }
}
