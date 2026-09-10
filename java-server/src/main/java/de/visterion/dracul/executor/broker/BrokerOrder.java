package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order as reported by the broker, one leg of a bracket (or standalone).
 *
 * <p>The last seven components exist because a single clientRef can carry several orders at once —
 * a bracket parent, its protective stop, its take-profit, and dead earlier placements — and the
 * first nine cannot tell them apart. {@code side} is the broker's raw side, lower-cased, and may be
 * {@code ""} (Saxo's embedded OCO children carry no {@code BuySell}). {@code type} is the broker's
 * raw order type, lower-cased ({@code limit}, {@code stopiftraded}, {@code market}). {@code
 * rawStatus} is the status string exactly as received, lower-cased, so a caller can refuse to treat
 * an UNRECOGNISED status as live even though {@link #status} conservatively maps it to WORKING.
 * {@code source} is {@code "open"} for a row that came out of the open-orders view and {@code
 * "history"} for one that came out of the audit history; it is the only way to know whether a row
 * is still resting at the broker. {@code filledAt} is null when the broker sent nothing parseable.
 */
public record BrokerOrder(String orderId, String clientRef, String symbol, OrderRole role, OrderStatus status,
        BigDecimal qty, BigDecimal filledQty, BigDecimal avgFillPrice, String parentId,
        String side, String type, String rawStatus, String source,
        BigDecimal limitPrice, BigDecimal stopPrice, Instant filledAt) {

    /**
     * Convenience shape for callers that only know the original nine fields — it nulls
     * {@code side}, {@code type}, {@code rawStatus}, {@code source}, {@code limitPrice},
     * {@code stopPrice} and {@code filledAt}. Every one of those nulls is read defensively
     * downstream (a null {@code source} counts as {@code "open"}; a null {@code rawStatus} is
     * never live), so this constructor is safe for tests and for internal copies, but a gateway
     * that produces real broker rows must use the canonical 16-arg form.
     */
    public BrokerOrder(String orderId, String clientRef, String symbol, OrderRole role, OrderStatus status,
            BigDecimal qty, BigDecimal filledQty, BigDecimal avgFillPrice, String parentId) {
        this(orderId, clientRef, symbol, role, status, qty, filledQty, avgFillPrice, parentId,
                null, null, null, null, null, null, null);
    }
}
