package de.visterion.dracul.strigoi.echo;

import java.time.LocalDate;
import java.util.Optional;

/** Provider-agnostic port for a symbol's next scheduled earnings date. */
public interface NextEarningsPort {
    /** Never throws; returns empty if unknown. */
    Optional<LocalDate> nextEarningsDate(String symbol);
}
