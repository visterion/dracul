package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/**
 * An open position as reported by the broker. {@code marketPrice} is strictly per-unit
 * (never a total market value). {@code openOrdersCount} is nullable — null means the
 * provider did not report a count, not that there are zero open orders.
 */
public record BrokerPosition(String symbol, String side, BigDecimal qty, BigDecimal avgEntryPrice,
        BigDecimal marketPrice, Integer openOrdersCount) {
}
