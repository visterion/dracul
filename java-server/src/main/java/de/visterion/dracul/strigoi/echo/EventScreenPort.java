package de.visterion.dracul.strigoi.echo;

import java.time.LocalDate;
import java.util.List;

/** Provider-agnostic confounder screen: returns the distinct confounder categories detected
 *  for a symbol since its report date (e.g. "m&a", "restatement"). Empty = clean. */
public interface EventScreenPort {
    /** Never throws; returns an empty list on any failure (treated as clean). */
    List<String> confounders(String symbol, LocalDate since);
}
