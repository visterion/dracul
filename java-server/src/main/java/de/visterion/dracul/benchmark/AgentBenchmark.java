package de.visterion.dracul.benchmark;

/** One Strigoi row of the summary. paired* is null when picked or rejected has no event (tech,
 *  momentum) and always for insider (spec §2.5) — the UI shows "—". coverageStart is the first
 *  captured day. excluded counts the events that never reach a mean (final review I3). */
public record AgentBenchmark(String agent, String coverageStart, SignalStats signal20, SignalStats signal60,
                             PairedStats paired20, PairedStats paired60, BenchmarkStatus status,
                             AgentExclusions excluded) {
}
