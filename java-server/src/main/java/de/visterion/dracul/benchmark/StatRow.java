package de.visterion.dracul.benchmark;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A measured benchmark_outcome row as the statistics read it (NO_ENTRY rows excluded). */
public record StatRow(EventKind kind, String agent, Boolean picked, int horizonDays, LocalDate entryDate,
                      BigDecimal excess) {
}
