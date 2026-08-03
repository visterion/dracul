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

    public boolean isHealthy() {
        return "healthy".equals(status);
    }
}
