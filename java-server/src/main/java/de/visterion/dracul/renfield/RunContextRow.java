package de.visterion.dracul.renfield;

import java.time.Instant;

/** One row of {@code renfield_run_context}: the depot-holding snapshot taken at trigger
 *  time for a single (run_id, symbol) pair. */
public record RunContextRow(String symbol, boolean held, String positionSource, Instant createdAt) {
}
