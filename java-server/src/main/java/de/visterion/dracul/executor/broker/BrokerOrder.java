package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/** An order as reported by the broker, one leg of a bracket (or standalone). */
public record BrokerOrder(String orderId, String clientRef, String symbol, OrderRole role, OrderStatus status,
        BigDecimal qty, BigDecimal filledQty, BigDecimal avgFillPrice, String parentId) {
}
