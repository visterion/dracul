package de.visterion.dracul.hunting;

import java.util.List;

/** Items from a market-data fetch plus the source's health for that fetch. */
public record DataSourceResult<T>(List<T> items, DataSourceHealth health) {

    public static <T> DataSourceResult<T> healthy(String source, List<T> items) {
        return new DataSourceResult<>(items, DataSourceHealth.healthy(source));
    }

    public static <T> DataSourceResult<T> unavailable(String source, String detail) {
        return new DataSourceResult<>(List.of(), DataSourceHealth.unavailable(source, detail));
    }
}
