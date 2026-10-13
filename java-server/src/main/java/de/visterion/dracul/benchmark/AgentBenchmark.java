package de.visterion.dracul.benchmark;

/** One Strigoi row of the summary. paired* is null when picked or rejected has no event
 *  (insider, tech, momentum) — the UI shows "—". coverageStart is the first captured day. */
public record AgentBenchmark(String agent, String coverageStart, SignalStats signal20, SignalStats signal60,
                             PairedStats paired20, PairedStats paired60, BenchmarkStatus status) {
}
