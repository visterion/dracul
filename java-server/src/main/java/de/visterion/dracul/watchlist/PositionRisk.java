package de.visterion.dracul.watchlist;

import java.math.BigDecimal;

/** gropar's read of a position's R-framework fields + the morning-report risk
 *  snapshot. entryDate is an ISO date string (yyyy-MM-dd, backfilled to added_at);
 *  initialStop is null until frozen. activeStop/nextTarget2r/currentClose are null
 *  until the next gropar fetch writes a snapshot. */
public record PositionRisk(
        String id, String entryDate, BigDecimal initialStop,
        BigDecimal activeStop, BigDecimal nextTarget2r, BigDecimal currentClose) {}
