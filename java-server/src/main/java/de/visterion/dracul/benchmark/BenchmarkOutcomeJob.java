package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Nightly benchmark measurement (spec 2026-10-13 §3.2). Read-only against Agora, writes only its
 * own tables, no LLM. Independent of the executor and momentum flags.
 *
 * <p>Per run: EUNL.DE and every needed {@code <CUR>EUR=X} pair are fetched once; at most
 * {@code max-symbols-per-run} distinct instruments, oldest pending events first, so the backlog
 * drains over several nights. "No data" (Agora served nothing / a request-scoped error) and
 * "source failed" (Agora did not answer) are separate counters (plan ruling R9).
 *
 * <p>Controller ruling: an empty EUNL.DE series (no exception, just no bars) must not build,
 * settle or measure anything — {@link TradingCalendar} extrapolates weekdays when empty, which
 * would silently fabricate a calendar for a dead benchmark feed. Likewise an empty instrument
 * series is routed to the no-data counter and the 3-night attempt rule BEFORE
 * {@link OutcomeCalculator#compute}, which would otherwise freeze it as a terminal NO_ENTRY on the
 * first night (an instrument with zero bars has no entry bar, and the EUNL calendar already has
 * plenty of trading days after T).
 *
 * <p>Final review I4 (revised): request-scoped Agora errors are only booked as "no data" once they
 * are confirmed — by a later successful fetch in the same night or by the night's natural end. When
 * {@value #MAX_CONSECUTIVE_REQUEST_ERRORS} request-scoped errors come in a row, EUNL.DE is probed
 * once more: if it answers with bars, the streak was real per-symbol no-data (Yahoo-unknown or dead
 * symbols surface as REQUEST errors) — a no-data night is booked for each and the night continues
 * with a reset streak; if the probe fails or is empty, the night stops as source trouble (streak
 * counts as source-failed, no no-data night). Without the probe, ten adjacent dead symbols at the
 * head of the oldest-first queue would stop every night before anything else is measured and never
 * be given up. For the same reason symbols that already have a no-data row (retries) are fetched
 * after symbols without one. A symbol given up after {@value #MAX_NO_DATA_NIGHTS} no-data nights
 * is retried once {@value #NO_DATA_RETRY_DAYS} days after its last no-data night.
 *
 * <p>Final review I1b: an event is done when its event identity OR its unique-key tuple
 * (kind, agent, symbol, entry_ref_date, horizon) already has a row.
 */
@Component
@ConditionalOnProperty(value = "dracul.benchmark.enabled", havingValue = "true", matchIfMissing = true)
public class BenchmarkOutcomeJob {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkOutcomeJob.class);

    static final int MAX_NO_DATA_NIGHTS = 3;
    static final int MAX_CONSECUTIVE_SOURCE_FAILURES = 3;
    static final int MAX_CONSECUTIVE_REQUEST_ERRORS = 10;
    static final int NO_DATA_RETRY_DAYS = 28;
    /** Bars requested for the EUNL.DE health probe after a request-error streak. */
    static final int PROBE_BARS = 5;
    /** EUNL/FX reach back past the oldest event's −60-trading-day picked window. */
    static final int CALENDAR_MARGIN_DAYS = 100;

    public record RunSummary(int eventsScanned, int rowsWritten, int pending, int noEntry, int delisted,
                             int noData, int unknownCurrency, int sourceFailed) {
    }

    private final BenchmarkOutcomeRepository repo;
    private final BenchmarkBars bars;
    private final int maxSymbolsPerRun;
    private final Clock clock;

    @Autowired
    public BenchmarkOutcomeJob(BenchmarkOutcomeRepository repo, BenchmarkBars bars,
                               @Value("${dracul.benchmark.max-symbols-per-run:150}") int maxSymbolsPerRun) {
        this(repo, bars, maxSymbolsPerRun, Clock.systemUTC());
    }

    BenchmarkOutcomeJob(BenchmarkOutcomeRepository repo, BenchmarkBars bars, int maxSymbolsPerRun, Clock clock) {
        this.repo = repo;
        this.bars = bars;
        this.maxSymbolsPerRun = maxSymbolsPerRun;
        this.clock = clock;
    }

    @Scheduled(cron = "${dracul.benchmark.cron:0 15 0 * * 2-6}", zone = "UTC")
    public void run() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("benchmark run failed", e);
        }
    }

    public RunSummary runOnce() {
        LocalDate today = LocalDate.now(clock);
        List<PreyRow> prey = repo.findPrey();
        List<CandidateRow> candidates = repo.findCandidates();
        if (prey.isEmpty() && candidates.isEmpty()) return logged(new RunSummary(0, 0, 0, 0, 0, 0, 0, 0));

        int calendarBars = BenchmarkBars.lookbackBars(oldestDate(prey, candidates, today).minusDays(CALENDAR_MARGIN_DAYS), today);
        PriceSeries eunl;
        try {
            eunl = bars.fetch(BenchmarkBars.BENCHMARK_SYMBOL, calendarBars);
        } catch (MarketDataException e) {
            log.warn("benchmark run: {} unavailable — nothing measured tonight: {}",
                    BenchmarkBars.BENCHMARK_SYMBOL, e.getMessage());
            return logged(new RunSummary(0, 0, 0, 0, 0, 0, 0, 1));
        }
        if (eunl.isEmpty()) {
            log.warn("benchmark run: {} served no bars — nothing measured tonight", BenchmarkBars.BENCHMARK_SYMBOL);
            return logged(new RunSummary(0, 0, 0, 0, 0, 0, 0, 1));
        }
        TradingCalendar cal = eunl.calendar();

        List<MeasureEvent> events = new ArrayList<>(
                EventBuilder.signalEvents(prey, cal, EventBuilder.latestOffers(candidates)));
        events.addAll(EventBuilder.candidateEvents(candidates, prey, cal));
        events.sort(Comparator.comparing(MeasureEvent::refAt));
        Set<String> done = new HashSet<>(repo.completedEventKeys());

        int pending = 0, noData = 0, unknownCurrency = 0, sourceFailed = 0, rowsWritten = 0, noEntry = 0, delisted = 0;
        Map<String, BenchmarkOutcomeRepository.NoDataState> noDataStates = repo.noDataStates();
        // Oldest-first within each group; symbols with a no-data row (retries) after fresh ones,
        // so dead symbols cannot pile up at the head of the queue (revised I4).
        Map<String, List<MeasureEvent>> fresh = new LinkedHashMap<>();
        Map<String, List<MeasureEvent>> retries = new LinkedHashMap<>();
        Map<String, String> currencyOf = new HashMap<>();
        for (MeasureEvent e : events) {
            if (openHorizons(e, done).isEmpty()) continue;
            if (!e.settledOn(today, cal)) { pending++; continue; }
            Optional<String> currency = CurrencyResolver.currencyOf(e.symbol());
            if (currency.isEmpty()) { unknownCurrency++; continue; }
            if (givenUp(noDataStates.get(e.symbol()), today)) { noData++; continue; }
            (noDataStates.containsKey(e.symbol()) ? retries : fresh)
                    .computeIfAbsent(e.symbol(), s -> new ArrayList<>()).add(e);
            currencyOf.put(e.symbol(), currency.get());
        }
        Map<String, List<MeasureEvent>> work = new LinkedHashMap<>();
        for (Map<String, List<MeasureEvent>> group : List.of(fresh, retries)) {
            for (Map.Entry<String, List<MeasureEvent>> g : group.entrySet()) {
                if (work.size() < maxSymbolsPerRun) work.put(g.getKey(), g.getValue());
                else pending += g.getValue().size();
            }
        }
        currencyOf.keySet().retainAll(work.keySet());

        Map<String, PriceSeries> fx = new HashMap<>();
        for (String cur : new TreeSet<>(currencyOf.values())) {
            if (CurrencyResolver.EUR.equals(cur)) continue;
            try {
                fx.put(cur, bars.fetch(CurrencyResolver.fxPair(cur), calendarBars));
            } catch (MarketDataException e) {
                log.warn("benchmark run: FX {} unavailable: {}", CurrencyResolver.fxPair(cur), e.getMessage());
            }
        }

        int consecutiveSourceFailures = 0;
        // Request-scoped errors not yet booked as no-data (symbol → its events), see class doc (I4).
        Map<String, List<MeasureEvent>> requestErrorStreak = new LinkedHashMap<>();
        boolean stopped = false;
        for (Map.Entry<String, List<MeasureEvent>> w : work.entrySet()) {
            String symbol = w.getKey();
            List<MeasureEvent> symbolEvents = w.getValue();
            if (stopped || consecutiveSourceFailures >= MAX_CONSECUTIVE_SOURCE_FAILURES) {
                pending += symbolEvents.size();
                continue;
            }
            PriceSeries series;
            try {
                series = bars.fetch(symbol, BenchmarkBars.lookbackBars(symbolEvents.getFirst().refDate().minusDays(10), today));
            } catch (MarketDataException e) {
                AgoraUnavailableException a = AgoraUnavailableException.unwrap(e);
                if (a != null && a.scope() == AgoraUnavailableException.Scope.REQUEST) {
                    consecutiveSourceFailures = 0;
                    requestErrorStreak.put(symbol, symbolEvents);
                    if (requestErrorStreak.size() >= MAX_CONSECUTIVE_REQUEST_ERRORS) {
                        if (benchmarkAnswers()) {
                            log.info("benchmark run: {} request-scoped errors in a row (last {}), {} still answers "
                                            + "— booked as no-data, night continues",
                                    requestErrorStreak.size(), symbol, BenchmarkBars.BENCHMARK_SYMBOL);
                            noData += bookNoData(requestErrorStreak, today);
                        } else {
                            log.warn("benchmark run: {} request-scoped errors in a row (last {}: {}) and the {} probe "
                                            + "failed — treated as source trouble, no no-data night recorded, rest of "
                                            + "the night deferred",
                                    requestErrorStreak.size(), symbol, e.getMessage(), BenchmarkBars.BENCHMARK_SYMBOL);
                            sourceFailed += requestErrorStreak.size();
                            for (List<MeasureEvent> streakEvents : requestErrorStreak.values()) pending += streakEvents.size();
                            requestErrorStreak.clear();
                            stopped = true;
                        }
                    }
                } else {
                    consecutiveSourceFailures++;
                    sourceFailed++;
                    pending += symbolEvents.size();
                    log.warn("benchmark run: bars for {} unavailable: {}", symbol, e.getMessage());
                }
                continue;
            }
            noData += bookNoData(requestErrorStreak, today);
            consecutiveSourceFailures = 0;
            if (series.isEmpty()) {
                // Controller ruling: empty bars go to no-data/attempt-limit, never into
                // OutcomeCalculator.compute (which would otherwise freeze the event as a terminal
                // NO_ENTRY on the first night the instrument happens to have zero bars).
                repo.recordNoData(symbol, today);
                noData += symbolEvents.size();
                continue;
            }
            repo.clearNoData(symbol);
            String currency = currencyOf.get(symbol);
            PriceSeries fxSeries = CurrencyResolver.EUR.equals(currency) ? null : fx.get(currency);
            for (MeasureEvent e : symbolEvents) {
                boolean eventPending = false, eventNoEntry = false, eventDelisted = false;
                for (int h : openHorizons(e, done)) {
                    OutcomeCalculator.Result r = OutcomeCalculator.compute(e, h, series, currency, eunl, cal, fxSeries);
                    if (r instanceof OutcomeCalculator.Complete(BenchmarkOutcome row)) {
                        if (repo.insert(row)) {
                            rowsWritten++;
                        } else {
                            // Plan ruling F4: the unique key already existed — not an error, just a
                            // concurrent write. Logged once: from the next night on the event is
                            // done by its unique-key tuple (final review I1b), never thrown.
                            log.warn("benchmark run: {} already has a row for {}/{}/{} h{} — skipped",
                                    e.kind(), e.agent(), e.symbol(), e.eventStart(), h);
                        }
                        done.add(BenchmarkOutcomeRepository.eventKey(e.kind(), e.agent(), e.symbol(), e.eventStart(), h));
                        done.add(BenchmarkOutcomeRepository.refKey(e.kind(), e.agent(), e.symbol(), e.refDate(), h));
                        if (row.exitKind() == ExitKind.NO_ENTRY) eventNoEntry = true;
                        if (row.exitKind() == ExitKind.DELISTED_OFFER || row.exitKind() == ExitKind.DELISTED_LAST) {
                            eventDelisted = true;
                        }
                    } else {
                        eventPending = true;
                    }
                }
                if (eventPending) pending++;
                if (eventNoEntry) noEntry++;
                if (eventDelisted) delisted++;
            }
        }
        if (consecutiveSourceFailures >= MAX_CONSECUTIVE_SOURCE_FAILURES) {
            // The night was cut by a source outage — unconfirmed request errors stay unbooked.
            for (List<MeasureEvent> streakEvents : requestErrorStreak.values()) pending += streakEvents.size();
        } else {
            noData += bookNoData(requestErrorStreak, today);
        }
        return logged(new RunSummary(events.size(), rowsWritten, pending, noEntry, delisted, noData,
                unknownCurrency, sourceFailed));
    }

    /** Health probe after a request-error streak: does EUNL.DE still answer with bars? */
    private boolean benchmarkAnswers() {
        try {
            return !bars.fetch(BenchmarkBars.BENCHMARK_SYMBOL, PROBE_BARS).isEmpty();
        } catch (MarketDataException e) {
            return false;
        }
    }

    /** Records one no-data night per confirmed request-error symbol; returns their event count. */
    private int bookNoData(Map<String, List<MeasureEvent>> streak, LocalDate today) {
        int events = 0;
        for (Map.Entry<String, List<MeasureEvent>> s : streak.entrySet()) {
            repo.recordNoData(s.getKey(), today);
            events += s.getValue().size();
        }
        streak.clear();
        return events;
    }

    /** Given up after {@value #MAX_NO_DATA_NIGHTS} nights; retried once {@value #NO_DATA_RETRY_DAYS}
     *  days after the last no-data night (a failed retry books a new night and restarts the wait). */
    static boolean givenUp(BenchmarkOutcomeRepository.NoDataState state, LocalDate today) {
        if (state == null || state.nights() < MAX_NO_DATA_NIGHTS) return false;
        return today.isBefore(state.lastNight().plusDays(NO_DATA_RETRY_DAYS));
    }

    private static List<Integer> openHorizons(MeasureEvent e, Set<String> done) {
        List<Integer> open = new ArrayList<>();
        for (int h : OutcomeCalculator.HORIZONS) {
            boolean byEvent = done.contains(BenchmarkOutcomeRepository.eventKey(e.kind(), e.agent(), e.symbol(), e.eventStart(), h));
            boolean byUniqueKey = done.contains(BenchmarkOutcomeRepository.refKey(e.kind(), e.agent(), e.symbol(), e.refDate(), h));
            if (!byEvent && !byUniqueKey) open.add(h);
        }
        return open;
    }

    private static LocalDate oldestDate(List<PreyRow> prey, List<CandidateRow> candidates, LocalDate today) {
        LocalDate oldest = today;
        for (PreyRow p : prey) if (p.date().isBefore(oldest)) oldest = p.date();
        for (CandidateRow c : candidates) if (c.asOfDate().isBefore(oldest)) oldest = c.asOfDate();
        return oldest;
    }

    private static RunSummary logged(RunSummary s) {
        log.info("benchmark run: events scanned={} rows written={} pending={} no-entry={} delisted={} "
                        + "no-data={} unknown-currency={} source-failed={}",
                s.eventsScanned(), s.rowsWritten(), s.pending(), s.noEntry(), s.delisted(), s.noData(),
                s.unknownCurrency(), s.sourceFailed());
        return s;
    }
}
