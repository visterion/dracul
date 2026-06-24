package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceResult;

import java.time.LocalDate;

/** Provider-agnostic source of recent earnings announcements for the PEAD hunt. */
public interface PeadEarningsSource {
    /** Announcements with report date in [from, to]. Never throws; returns unavailable health on failure. */
    DataSourceResult<EarningsObservation> recent(LocalDate from, LocalDate to);

    /** Stable id for logging / source selection (e.g. "finnhub", "yahoo"). */
    String id();
}
