package de.visterion.dracul.depot;

import java.math.BigDecimal;

/** A single order, as returned by {@code get_orders}. {@code submittedAt}/{@code filledAt} are
 *  nullable ISO-8601 broker timestamps; {@code avgFillPrice} the broker's realized average fill;
 *  {@code limitPrice}/{@code stopPrice} are nullable broker-reported limit/stop prices — an order
 *  carries at most one of the two. */
public record DepotOrder(String brokerOrderId, String symbol, String side, BigDecimal qty,
                          String type, String status, String role, String parentId,
                          String submittedAt, String filledAt, BigDecimal avgFillPrice,
                          BigDecimal limitPrice, BigDecimal stopPrice) {
}
