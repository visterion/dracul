package de.visterion.dracul.strigoi.echo;

/** Provider-agnostic port for earnings-quality fundamentals (Sloan accruals). */
public interface FundamentalsPort {
    /** Never throws; returns {@link AccrualMetrics#unavailable()} on any failure. */
    AccrualMetrics accruals(String symbol);
}
