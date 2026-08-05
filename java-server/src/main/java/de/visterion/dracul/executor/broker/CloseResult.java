package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of a flatten/close request against an open position. {@code protectiveLegs} carries
 * any stop legs Agora restored, re-sized to the remainder, after a partial close — empty when
 * nothing was restored (e.g. a full flatten). {@code legsCollapsed} is true when fewer legs
 * came back than were cancelled because the remainder was too small to give each one a share;
 * reconcile against {@code protectiveLegs}' {@code replaces} ids, never against a count.
 */
public record CloseResult(BigDecimal closedQty, BigDecimal remainingQty, BigDecimal avgFillPrice, String orderRef,
        List<RestoredLeg> protectiveLegs, boolean legsCollapsed) {
}
