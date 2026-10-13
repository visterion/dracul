package de.visterion.dracul.benchmark;

import java.util.List;

/** GET /api/benchmark/summary. depot is null when not visible to the caller or not computable. */
public record BenchmarkSummary(String benchmark, String decisionDate, int minEvents, int minWeeks,
                               List<AgentBenchmark> agents, DepotBenchmark depot) {
}
