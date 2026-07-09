package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/** Result of modifying a bracket's stop-loss and/or take-profit legs. */
public record ModifyResult(String orderId, BigDecimal newStop, BigDecimal newTarget, boolean accepted) {
}
