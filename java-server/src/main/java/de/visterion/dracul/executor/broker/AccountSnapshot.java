package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/** Broker account cash/buying-power snapshot for a connection. */
public record AccountSnapshot(BigDecimal cash, BigDecimal buyingPower, String currency) {
}
