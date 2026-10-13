package de.visterion.dracul.benchmark;

/** Excluded counts of one Strigoi: SIGNAL events, and CANDIDATE events split into picked and
 *  rejected (final review I3). {@code total} is the sum of all three groups. */
public record AgentExclusions(ExcludedCounts signal, ExcludedCounts picked, ExcludedCounts rejected, int total) {

    public static final AgentExclusions NONE =
            new AgentExclusions(ExcludedCounts.ZERO, ExcludedCounts.ZERO, ExcludedCounts.ZERO, 0);

    public static AgentExclusions of(ExcludedCounts signal, ExcludedCounts picked, ExcludedCounts rejected) {
        return new AgentExclusions(signal, picked, rejected, signal.total() + picked.total() + rejected.total());
    }
}
