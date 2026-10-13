package de.visterion.dracul.benchmark;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Builds GET /api/benchmark/summary (spec §4.1). One row per agent that has a measured outcome
 *  or a captured candidate, sorted by name. Status from the 60-day SIGNAL statistics.
 *  {@code minWeeks} is the weeks needed for the 60-day interval that drives the status (24). */
@Service
public class BenchmarkSummaryService {

    /** Captured, but without a usable control group: paired comparison is never shown (spec §2.5). */
    static final Set<String> SIGNAL_ONLY_AGENTS = Set.of("strigoi-insider");

    private final BenchmarkOutcomeRepository outcomes;
    private final ScreenCandidateRepository candidates;
    private final DepotBenchmarkService depot;
    private final String decisionDate;

    public BenchmarkSummaryService(BenchmarkOutcomeRepository outcomes, ScreenCandidateRepository candidates,
                                   DepotBenchmarkService depot,
                                   @Value("${dracul.benchmark.decision-date:2027-04-13}") String decisionDate) {
        this.outcomes = outcomes;
        this.candidates = candidates;
        this.depot = depot;
        this.decisionDate = LocalDate.parse(decisionDate.trim()).toString();
    }

    public BenchmarkSummary summary(String userEmail) {
        List<StatRow> rows = outcomes.findForStats();
        Map<String, LocalDate> coverage = candidates.coverageStart();
        SortedSet<String> agents = new TreeSet<>(coverage.keySet());
        rows.forEach(r -> agents.add(r.agent()));

        List<AgentBenchmark> out = new ArrayList<>();
        for (String agent : agents) {
            SignalStats s20 = BenchmarkStats.signal(obs(rows, agent, EventKind.SIGNAL, null, 20), 20);
            SignalStats s60 = BenchmarkStats.signal(obs(rows, agent, EventKind.SIGNAL, null, 60), 60);
            PairedStats p20 = paired(rows, agent, 20);
            PairedStats p60 = paired(rows, agent, 60);
            LocalDate cov = coverage.get(agent);
            out.add(new AgentBenchmark(agent, cov == null ? null : cov.toString(), s20, s60, p20, p60,
                    BenchmarkStats.status(s60)));
        }
        return new BenchmarkSummary(BenchmarkBars.BENCHMARK_SYMBOL, decisionDate, BenchmarkStats.MIN_EVENTS,
                BenchmarkStats.minWeeks(BenchmarkStats.STATUS_HORIZON), out, depot.block(userEmail));
    }

    /** Spec §2.5: insider has no control group (the LLM picks ~all clusters) — paired is null even
     *  if a few rejected clusters exist (final review M4). */
    private static PairedStats paired(List<StatRow> rows, String agent, int horizon) {
        if (SIGNAL_ONLY_AGENTS.contains(agent)) return null;
        return BenchmarkStats.paired(obs(rows, agent, EventKind.CANDIDATE, true, horizon),
                obs(rows, agent, EventKind.CANDIDATE, false, horizon), horizon);
    }

    static List<BenchmarkStats.Obs> obs(List<StatRow> rows, String agent, EventKind kind, Boolean picked, int horizon) {
        return rows.stream()
                .filter(r -> r.agent().equals(agent) && r.kind() == kind && r.horizonDays() == horizon
                        && (picked == null || picked.equals(r.picked())))
                .map(r -> new BenchmarkStats.Obs(r.entryDate(), r.excess().doubleValue()))
                .toList();
    }
}
