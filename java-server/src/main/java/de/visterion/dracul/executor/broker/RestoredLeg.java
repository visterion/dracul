package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/**
 * A protective stop leg Agora re-issued after a partial flatten. {@code replaces} is the
 * broker order id Dracul held in its book before the flatten; {@code orderId} is the new id
 * the broker assigned to the re-sized replacement. Dracul must repoint its book from
 * {@code replaces} to {@code orderId} — reconciling by count instead of by id silently loses
 * track of legs when {@code legsCollapsed} is true.
 */
public record RestoredLeg(String replaces, String orderId, BigDecimal qty, BigDecimal price) {
}
