package de.visterion.dracul.benchmark;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One {@code benchmark_outcome} row (V55). Returns are fractions in EUR. */
public record BenchmarkOutcome(EventKind kind, String agent, String symbol, LocalDate eventStartDate,
                               LocalDate entryRefDate, Instant entryRefAt, Boolean picked, int horizonDays,
                               LocalDate entryDate, BigDecimal entryPrice, LocalDate exitDate,
                               BigDecimal exitPrice, String currency, BigDecimal fxEntry, BigDecimal fxExit,
                               BigDecimal returnEur, BigDecimal benchmarkReturnEur, BigDecimal excess,
                               ExitKind exitKind) {

    /** Terminal row for an event that can never be entered (spec §3.2, review M3). */
    public static BenchmarkOutcome noEntry(MeasureEvent e, int horizon, String currency) {
        return new BenchmarkOutcome(e.kind(), e.agent(), e.symbol(), e.eventStart(), e.refDate(), e.refAt(),
                e.picked(), horizon, null, null, null, null, currency, null, null, null, null, null,
                ExitKind.NO_ENTRY);
    }
}
