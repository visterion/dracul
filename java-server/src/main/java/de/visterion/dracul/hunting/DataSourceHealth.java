package de.visterion.dracul.hunting;

import java.time.Instant;

/** Health of a market-data source for a single fetch. status: "healthy" | "unavailable". */
public record DataSourceHealth(String status, String source, String detail, Instant checkedAt) {

    public static DataSourceHealth healthy(String source) {
        return new DataSourceHealth("healthy", source, null, Instant.now());
    }

    public static DataSourceHealth unavailable(String source, String detail) {
        return new DataSourceHealth("unavailable", source, detail, Instant.now());
    }

    public boolean isHealthy() {
        return "healthy".equals(status);
    }
}
