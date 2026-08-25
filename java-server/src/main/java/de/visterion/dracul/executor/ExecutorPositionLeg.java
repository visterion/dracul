package de.visterion.dracul.executor;

import java.math.BigDecimal;

/**
 * One broker tranche of a position. The broker holds each tranche as a separate
 * position with its own stop order; this row is the book's record of that leg.
 *
 * <p>{@code qty} follows the same rule as {@link ExecutorPosition#qty()}: shares
 * actually held, never shares intended.
 */
public record ExecutorPositionLeg(
        Long id,
        long positionId,
        int tranche,
        String entryOrderId,
        String stopOrderId,
        BigDecimal qty,
        String status,
        BigDecimal exitPrice,
        String exitReason,
        String closedAt) {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";
    public static final String CANCELLED = "CANCELLED";

    public boolean isOpen() { return OPEN.equals(status); }
}
