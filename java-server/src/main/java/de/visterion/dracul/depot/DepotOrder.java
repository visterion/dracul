package de.visterion.dracul.depot;

import java.math.BigDecimal;

/** A single order, as returned by {@code get_orders}. */
public record DepotOrder(String brokerOrderId, String symbol, String side, BigDecimal qty,
                          String type, String status, String role) {
}
