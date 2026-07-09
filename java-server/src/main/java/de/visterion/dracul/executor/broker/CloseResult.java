package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/** Result of a flatten/close request against an open position. */
public record CloseResult(BigDecimal closedQty, BigDecimal remainingQty, BigDecimal avgFillPrice, String orderRef) {
}
