package de.visterion.dracul.strigoi.echo;

/** Provider-agnostic port for per-symbol equity metrics (beta, market cap, sector). */
public interface EquityMetricsPort {
    /** Never throws; returns {@link EquityMetrics#unavailable()} on any failure. */
    EquityMetrics metrics(String symbol);
}
