package de.visterion.dracul.benchmark;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** One prey as the benchmark sees it: discovered_by, upper-cased symbol, discovered_at. */
public record PreyRow(String agent, String symbol, Instant discoveredAt) {

    public LocalDate date() {
        return discoveredAt.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
