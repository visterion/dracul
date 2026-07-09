package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/** A request to place an entry order with attached stop-loss and take-profit legs. */
public record BracketRequest(String symbol, String side, BigDecimal qty, BigDecimal limitPrice,
        BigDecimal stopLossStop, BigDecimal takeProfitLimit, String clientRef, String timeInForce) {
}
