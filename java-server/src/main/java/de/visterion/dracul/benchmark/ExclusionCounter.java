package de.visterion.dracul.benchmark;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Final review I3: makes the events that never enter a mean visible per Strigoi. Pure — the
 * repository supplies the rows. Groups: SIGNAL; CANDIDATE picked; CANDIDATE rejected.
 * <ul>
 *   <li>NO_ENTRY: distinct events with a terminal NO_ENTRY row (exact, from benchmark_outcome).</li>
 *   <li>Unknown currency / given up: never-measured (agent, symbol) pairs whose symbol has no
 *       known quote currency, or is given up after ≥ {@value BenchmarkOutcomeJob#MAX_NO_DATA_NIGHTS}
 *       no-data nights. Counted per pair, not per event (events are only built nightly against the
 *       EUNL calendar); a candidate pair counts as picked when the same agent has a prey for the
 *       symbol.</li>
 * </ul>
 */
final class ExclusionCounter {

    /** A terminal NO_ENTRY count for one (kind, agent, picked) group. */
    record NoEntryCount(EventKind kind, String agent, Boolean picked, int events) {
    }

    /** A (kind, agent, symbol) pair without any benchmark_outcome row; picked null for SIGNAL. */
    record UnmeasuredPair(EventKind kind, String agent, String symbol, Boolean picked) {
    }

    private ExclusionCounter() {
    }

    static Map<String, AgentExclusions> count(List<NoEntryCount> noEntry, List<UnmeasuredPair> unmeasured,
                                              Map<String, BenchmarkOutcomeRepository.NoDataState> noData) {
        Map<String, ExcludedCounts[]> acc = new HashMap<>();
        for (NoEntryCount c : noEntry) {
            add(acc, c.agent(), c.kind(), c.picked(), c.events(), 0, 0);
        }
        for (UnmeasuredPair p : unmeasured) {
            if (CurrencyResolver.currencyOf(p.symbol()).isEmpty()) {
                add(acc, p.agent(), p.kind(), p.picked(), 0, 0, 1);
            } else {
                BenchmarkOutcomeRepository.NoDataState s = noData.get(p.symbol());
                if (s != null && s.nights() >= BenchmarkOutcomeJob.MAX_NO_DATA_NIGHTS) {
                    add(acc, p.agent(), p.kind(), p.picked(), 0, 1, 0);
                }
            }
        }
        Map<String, AgentExclusions> out = new HashMap<>();
        acc.forEach((agent, g) -> out.put(agent, AgentExclusions.of(g[0], g[1], g[2])));
        return out;
    }

    private static void add(Map<String, ExcludedCounts[]> acc, String agent, EventKind kind, Boolean picked,
                            int noEntry, int noData, int unknownCurrency) {
        ExcludedCounts[] g = acc.computeIfAbsent(agent,
                a -> new ExcludedCounts[]{ExcludedCounts.ZERO, ExcludedCounts.ZERO, ExcludedCounts.ZERO});
        int i = kind == EventKind.SIGNAL ? 0 : Boolean.TRUE.equals(picked) ? 1 : 2;
        g[i] = g[i].plus(noEntry, noData, unknownCurrency);
    }
}
