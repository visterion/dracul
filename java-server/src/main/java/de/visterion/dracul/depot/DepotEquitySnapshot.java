package de.visterion.dracul.depot;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One measured point of a depot's equity time series (V47). {@code equity} and {@code cash} are
 * the broker's own figures in the account currency -- never a sum across currencies.
 *
 * <p>{@code asOf} is a LABEL, not a comparison instant: for {@code DAILY} it is 00:00:00Z of the
 * UTC calendar day the measurement was taken, for {@code INTRADAY} the run time truncated to the
 * minute.
 */
public record DepotEquitySnapshot(long id, String connection, Instant asOf, String granularity,
                                  BigDecimal equity, BigDecimal cash, BigDecimal positionsValue,
                                  String currency, BigDecimal externalFlow, String source) {
}
