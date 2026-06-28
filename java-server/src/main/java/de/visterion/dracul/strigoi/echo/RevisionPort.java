package de.visterion.dracul.strigoi.echo;

/** Provider-agnostic port for an analyst-revision proxy. */
public interface RevisionPort {
    /** Never throws; returns {@link EarningsRevisions#unavailable()} on any failure. */
    EarningsRevisions revisions(String symbol);
}
