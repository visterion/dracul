package de.visterion.dracul.hunting;

import java.time.Instant;

/**
 * Health of a market-data source for a single fetch. status: "healthy" | "unavailable".
 *
 * <p>{@code partial} / {@code truncated} are deliberately NOT a third status value. Every hunter
 * prompt contains the clause "if data_source_health.status is unavailable, return exactly
 * {"prey": []}" — flipping the status for a degraded-but-usable fetch would throw away the
 * candidates we did get. The flags say "what you see is incomplete" without saying "you saw
 * nothing".
 */
public record DataSourceHealth(String status, String source, String detail, Instant checkedAt,
                               boolean partial, boolean truncated) {

    public static DataSourceHealth healthy(String source) {
        return new DataSourceHealth("healthy", source, null, Instant.now(), false, false);
    }

    public static DataSourceHealth unavailable(String source, String detail) {
        return new DataSourceHealth("unavailable", source, detail, Instant.now(), false, false);
    }

    /** Usable data that is known to be incomplete: status stays "healthy" (see class doc). */
    public static DataSourceHealth degraded(String source, String detail,
                                            boolean partial, boolean truncated) {
        return new DataSourceHealth("healthy", source, detail, Instant.now(), partial, truncated);
    }

    /**
     * ORs a SECOND, Dracul-side degradation into a source's health, keeping both details.
     *
     * <p>Two independent sources of incompleteness meet at every hunter: the upstream fetch may
     * have come back partial/truncated, and Dracul's own post-processing (a candidate cap, a
     * per-candidate enrichment that failed) may have lost more on top. Neither may overwrite the
     * other, or the run looks clean while half its input is missing.
     *
     * <p>An {@code unavailable} status is passed through UNTOUCHED — {@link #degraded} always
     * yields {@code "healthy"}, so degrading a real outage would upgrade a total failure into a
     * usable one and defeat the "return exactly {@code {"prey": []}}" clause every hunter prompt
     * carries. A no-op call returns the very same instance, so a clean payload looks exactly as
     * it did before.
     */
    public static DataSourceHealth degradedWith(DataSourceHealth base, String detail,
                                                boolean partial, boolean truncated) {
        if (!base.isHealthy()) return base;
        if (!partial && !truncated) return base;
        String merged = base.detail() == null || base.detail().isBlank()
                ? detail
                : base.detail() + "; " + detail;
        return degraded(base.source(), merged,
                base.partial() || partial, base.truncated() || truncated);
    }

    public boolean isHealthy() {
        return "healthy".equals(status);
    }
}
