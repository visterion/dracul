package de.visterion.dracul.watchlist;

import java.math.BigDecimal;

/** gropar's read of a position's R-framework fields. entryDate is an ISO date
 *  string (yyyy-MM-dd, backfilled to added_at); initialStop is null until frozen. */
public record PositionRisk(String id, String entryDate, BigDecimal initialStop) {}
