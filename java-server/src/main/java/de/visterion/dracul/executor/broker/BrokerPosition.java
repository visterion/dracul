package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/** An open position as reported by the broker. */
public record BrokerPosition(String symbol, String side, BigDecimal qty, BigDecimal avgEntryPrice,
        BigDecimal marketPrice) {
}
