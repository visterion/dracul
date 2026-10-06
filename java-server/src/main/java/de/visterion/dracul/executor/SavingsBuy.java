package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * One {@code savings_plan_buy} row (V54, spec 2026-10-06 §7): a savings add of one position in one
 * month, from the intent row written BEFORE the broker call to its terminal outcome. Never a
 * tranche — nothing here is ever copied into {@code tranche2_*} or {@code executor_position_leg}.
 */
public record SavingsBuy(Long id, String month, long positionId, String symbol, BigDecimal qty,
        BigDecimal limitPrice, BigDecimal limitEur, String clientRef, String entryOrderId,
        String childStopOrderId, BigDecimal qtyBefore, BigDecimal avgBefore, BigDecimal stopBefore,
        String status, String newStopOrderId, String skipReason, BigDecimal windowStopQty,
        BigDecimal targetQty, BigDecimal targetStop, BigDecimal fillQty, BigDecimal fillPrice,
        BigDecimal avgAfter, BigDecimal stopAfter, String tif, Instant createdAt, Instant updatedAt) {

    public static final String PLACING = "PLACING";
    public static final String PLACED = "PLACED";
    public static final String CONSOLIDATING = "CONSOLIDATING";
    public static final String UNPROTECTED = "UNPROTECTED";
    public static final String EMERGENCY_EXIT = "EMERGENCY_EXIT";
    public static final String SKIPPED = "SKIPPED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CONSOLIDATED = "CONSOLIDATED";
    public static final String WINDOW_STOPPED = "WINDOW_STOPPED";
    public static final String CLOSED_WITH_POSITION = "CLOSED_WITH_POSITION";

    /** The five statuses that make a position "in flight" (spec §6.1, R2 M1). */
    public static final Set<String> IN_FLIGHT =
            Set.of(PLACING, PLACED, CONSOLIDATING, UNPROTECTED, EMERGENCY_EXIT);

    public boolean inFlight() {
        return IN_FLIGHT.contains(status);
    }

    /** {@code sp-<positionId>-<yyyyMM>}; {@code month} is {@code YYYY-MM}. */
    public static String clientRef(long positionId, String month) {
        return "sp-" + positionId + "-" + month.replace("-", "");
    }
}
